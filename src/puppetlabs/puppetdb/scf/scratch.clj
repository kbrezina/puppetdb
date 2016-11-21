(ns puppetlabs.puppetdb.scf.scratch
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.testutils.db :as tudb]
            [clj-time.core :as time]
            [clj-time.format :as tformat]
            [clj-time.coerce :as tcoerce]
            [clojure.java.jdbc :as sql]
            [clojure.set :as set])
  (:import [org.postgresql.util PGTimestamp]
           [java.util Calendar]))

(def node-cnt 25000)

(defn ral-package-seq []
  (mapv (fn [[package-name package-map]]
          {:package-name package-name
           :version (get package-map "ensure")
           :provider "yum"})
        (-> (slurp "packages.json")
            json/parse-string
            (get "package"))))

(defn- assoc-hash [m]
  (assoc m
    :hash (-> m
              hash/generic-identity-hash
              sutils/munge-hash-for-storage)))

(defn- deduplicate-by-package-name [col]
  (-> (reduce (fn [m item] (assoc m (:package-name item) item))
              {}
              col)
      vals))

(defn create-node-specific-packages [node-name packages]
  (map (fn [{:keys [id name version]}]
         {:package-name name
          :package-id id
          :version version})
       (apply jdbc/insert!
              :packages
              (map (fn [{:keys [package-name version]}]
                     (assoc-hash {:name (format "%s-%s" package-name node-name)
                                  :version version
                                  :provider "yum"}))
                   (-> (repeatedly 5 #(rand-nth packages))
                       deduplicate-by-package-name)))))

(defn populate-common-packages [db]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (apply jdbc/insert! :packages (map (fn [{:keys [package-name version provider]} ]
                                           (assoc-hash {:name package-name
                                                        :version (if (coll? version)
                                                                   (first version)
                                                                   version)
                                                        :provider provider}))
                                         (ral-package-seq))))))

(defn all-packages [db]
  (jdbc/with-db-connection db
    (mapv (fn [{:keys [id name version]}]
              {:package-name name
               :package-id id
               :version version})
            (jdbc/query-to-vec "select id, name, version from packages"))))

(defn create-fake-nodes [db]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (doseq [node-name (map #(str "node-" %) (range 0 node-cnt))]
        (scf-storage/add-certname! node-name)))))

(defn node-info [db]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (reduce (fn [acc {:keys [id certname]}]
                (assoc acc id certname))
              (sorted-map)
              (jdbc/query-to-vec "select id, certname from certnames")))))

(defn- munge-timestamp [date-time]
  (PGTimestamp.
    (tcoerce/to-long date-time)
    (. Calendar getInstance)))

(defn munge-range [date-time]
  (sutils/str->pgobject
   "tstzrange"
   (format "[%s,]" (tformat/unparse (:basic-date-time tformat/formatters) date-time))))

(defn insert-packages-for-certname [db certname-id certname packages]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (let [current-time (time/now)
            node-specific-packages (create-node-specific-packages certname packages)]
        (apply jdbc/insert! :package_lifetimes
               (map (fn [{:keys [package-id]}]
                      {:package_id package-id
                       :certname_id certname-id
                       :time_range (munge-range current-time)})
                    (concat packages node-specific-packages)))))))

(defn- time-range [db package-id certname-id timestamp]
  (jdbc/with-db-connection db
    (-> (jdbc/query-to-vec "select time_range from package_lifetimes where package_id = ? and certname_id = ? and time_range @> ?"
                           package-id
                           certname-id
                           (munge-timestamp timestamp))
        first
        (get :time_range))))

(defn- close-package-period! [db package-id certname-id timestamp]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (let [pg-timestamp (munge-timestamp timestamp)]
        (jdbc/execute! ["UPDATE package_lifetimes SET time_range = tstzrange (lower(time_range), ?) WHERE package_id = ? and certname_id = ? and time_range @> ?"
                        pg-timestamp
                        package-id
                        certname-id
                        pg-timestamp])))))

(defn- add-package-period! [db package-id certname-id timestamp]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (when-not (time-range db package-id certname-id timestamp)
        (jdbc/insert! :package_lifetimes
                      {:package_id package-id
                       :certname_id certname-id
                       :time_range (munge-range timestamp)})))))

(defn- store-diff [db package-id certname-id timestamp operation]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (case operation
            :remove (close-package-period! db package-id certname-id timestamp)
            :add    (add-package-period! db package-id certname-id timestamp)))))

(defn- current-packages [db certname-id timestamp]
  (jdbc/with-db-connection db
    (jdbc/query-to-vec "select pc.* from packages pc join package_lifetimes pl on pl.package_id = pc.id where pl.certname_id = ? and pl.time_range @> ?"
                       certname-id
                       (munge-timestamp timestamp))))

(defn- store-package [db package]
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (if-let [stored-package (-> (jdbc/query-to-vec "select * from packages where hash = ?" (:hash package))
                                  first)]
        stored-package
        (-> (jdbc/insert! :packages package)
            first)))))

(defn- gen-agent-packages [template-packages upgrade-cnt]
  "Generate a sequence of packages from the given template and change version to upgrade-cnt of them."
  (let [packages-to-upgrade        (repeatedly upgrade-cnt
                                               #(rand-nth template-packages))
        packages-to-upgrade-hashes (reduce (fn[s item] (conj s (:hash item)))
                                           #{}
                                           packages-to-upgrade)]
    (concat (map (fn [{:keys [name version provider]}]
                   (assoc-hash {:name name
                                :version (str version "1")
                                :provider provider}))
                 packages-to-upgrade)
            (filter (fn [{:keys [hash]}]
                      (not (contains? packages-to-upgrade-hashes hash)))
                    template-packages))))

(defn- hashes->set [coll]
  (->> coll
       (map #(:hash %))
       set))

(defn- compare-package-colls [package-coll-old package-coll-new]
  (let [hashes-old (hashes->set package-coll-old)
        hashes-new (hashes->set package-coll-new)
        removed (set/difference hashes-old hashes-new)
        added   (set/difference hashes-new hashes-old)]
    {:remove (filter #(contains? removed (:hash %)) package-coll-old)
     :add    (filter #(contains? added (:hash %)) package-coll-new)}))

(defn- agent-run [db certname-id timestamp upgrade-cnt]
  "Upgrade upgrade-cnt packages on the given node"
  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (let [pdb-packages   (current-packages db certname-id timestamp)
            agent-packages (gen-agent-packages pdb-packages upgrade-cnt)
            package-diffs  (compare-package-colls pdb-packages agent-packages)]
        (doseq [package (:add package-diffs)]
          (store-diff db
                      (:id (store-package db package))
                      certname-id
                      timestamp
                      :add))
        (doseq [package (:remove package-diffs)]
          (store-diff db
                      (:id package)
                      certname-id
                      timestamp
                      :remove))))))

(defn- timestamp->string [timestamp]
  (tformat/unparse (:date tformat/formatters) timestamp))

(defn insert-all-the-things [db]
  (println ">>> running insert-all-the-things")

  (jdbc/with-db-connection db
    (jdbc/with-db-transaction []
      (create-fake-nodes db)
      (populate-common-packages db)
      (let [node-map (node-info db)
            packages (all-packages db)]
        (doseq [[node-id node-name] node-map]
          (insert-packages-for-certname db node-id node-name packages))

        (println ">>> running agent simulation")

        (let [time-seq (iterate #(time/plus % (time/days 1)) (time/now))]

          ;; simulate 7 agent runs on every agent
          (doseq [timestamp (take 7 time-seq)
                  :let [_ (println "  >>>" (timestamp->string timestamp))]
                  [node-id _] node-map]
            (agent-run db node-id timestamp 20))

          ;; simulate system upgrade on 3000 nodes
          (let [upgrade-timestamp (nth time-seq 7)]
            (println "  >>>" (timestamp->string upgrade-timestamp) "- system upgrade")
            (doseq [[node-id _] (take 3000 node-map)]
              (agent-run db node-id upgrade-timestamp 400))
            (doseq [[node-id _] (drop 3000 node-map)]
              (agent-run db node-id upgrade-timestamp 20)))

          ;; simulate 14 agent runs on every agent
          (doseq [timestamp (take 14 (drop 8 time-seq))
                  :let [_ (println "  >>>" (timestamp->string timestamp))]
                  [node-id _] node-map]
            (agent-run db node-id timestamp 20)))))))


(def db (tudb/init-db (tudb/create-temp-db) false))
(insert-all-the-things db)
