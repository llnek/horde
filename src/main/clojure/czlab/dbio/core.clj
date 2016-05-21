;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; Copyright (c) 2013-2016, Kenneth Leung. All rights reserved.

(ns ^{:doc ""
      :author "kenl" }

  czlab.dbio.core

  (:import
    [java.util GregorianCalendar
     HashMap
     TimeZone Properties]
    [czlab.dbio MetaCache
     Schema
     BoneCPHook
     DBIOError
     SQLr
     JDBCPool
     JDBCInfo]
    [java.sql SQLException
     DatabaseMetaData
     Connection
     Driver
     DriverManager]
    [java.lang Math]
    [czlab.crypto PasswordAPI]
    [com.jolbox.bonecp BoneCP BoneCPConfig]
    [org.apache.commons.lang3 StringUtils])

  (:require
    [czlab.xlib.format :refer [writeEdnString]]
    [czlab.xlib.str :refer
     [lcase ucase strim
      embeds? addDelim! hasNoCase? hgl?]]
    [czlab.xlib.core :refer
     [tryc try! trylet! trap!
      rootCause stripNSPath
      getTypeId interject nnz nbf juid]]
    [czlab.xlib.logging :as log]
    [clojure.string :as cs]
    [clojure.set :as cset]
    [czlab.crypto.codec :as codec]
    [czlab.xlib.meta :refer [forname]])

  (:use [flatland.ordered.set]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

(def ^:dynamic ^MetaCache *META-CACHE* nil)
(def ^:dynamic *USE_DDL_SEP* true)
(def ^:dynamic *DDL_BVS* nil)
(def ^:dynamic *JDBC-INFO* nil)
(def ^:dynamic *JDBC-POOL* nil)

(defonce ^String COL_LASTCHANGED "DBIO_LASTCHANGED")
(defonce ^String COL_CREATED_ON "DBIO_CREATED_ON")
(defonce ^String COL_CREATED_BY "DBIO_CREATED_BY")
(defonce ^String COL_LHS_TYPEID "LHS_TYPEID")
(defonce ^String COL_LHS_ROWID "LHS_ROWID")
(defonce ^String COL_RHS_TYPEID "RHS_TYPEID")
(defonce ^String COL_RHS_ROWID "RHS_ROWID")
(defonce ^String COL_ROWID "DBIO_ROWID")
(defonce ^String COL_VERID "DBIO_VERID")
(defonce ^String DDL_SEP "-- :")

(def ^:private ^String _NSP "czc.dbio.core" )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro uc-ent ^String [ent] `(cs/upper-case (name ~ent)))
(defmacro lc-ent ^String [ent] `(cs/lower-case (name ~ent)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn ese

  "Escape string entity for sql"

  (^String [c1 ent c2] (str c1 (uc-ent ent) c2))
  (^String [ent] (uc-ent ent))
  (^String [ch ent] (str ch (uc-ent ent) ch)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn gtable

  "Get the table name (escaped) of this model"

  ^String
  [model]

  (ese (:table model)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; have to be function , not macro as this is passed into another higher
;; function - merge.
(defn mergeMeta

  "Merge 2 meta maps"

  [m1 m2]

  (merge m1 m2))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbTablename

  "The table-name defined for this model"

  (^String
    [model]
    (:table model))

  (^String
    [model-id cache]
    (dbTablename (cache model-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbColname

  "The column-name defined for this field"

  (^String
    [fdef]
    (:column fdef))

  (^String
    [fld-id model]
    (-> (:fields (meta model))
        (get fld-id)
        (:column))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro eseLHS [] `(ese COL_LHS_ROWID))
(defmacro eseRHS [] `(ese COL_RHS_ROWID))
(defmacro eseOID [] `(ese COL_ROWID))
(defmacro eseVID [] `(ese COL_VERID))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mkJdbc

  "Make a JDBCInfo record"

  ^JDBCInfo
  [^String id cfg ^PasswordAPI pwdObj]

  ;;(log/debug "JDBC id= %s, cfg = %s" id cfg)
  (let [server (:server cfg)]
    (reify

      JDBCInfo

      (getDriver [_] (:driver cfg))
      (getUser [_] (:user cfg))
      (getId [_] id)
      (getUrl [_] (if-not (empty? server)
                    server
                    (:url cfg)))
      (getPwd [_] (str pwdObj)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defonce Postgresql :postgresql)
(defonce SQLServer :sqlserver)
(defonce Oracle :oracle)
(defonce MySQL :mysql)
(defonce H2 :h2)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defonce DBTYPES {
    :sqlserver {:test-string "select count(*) from sysusers" }
    :postgresql { :test-string "select 1" }
    :mysql { :test-string "select version()" }
    :h2  { :test-string "select 1" }
    :oracle { :test-string "select 1 from DUAL" }
  })

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn ^:no-doc mkDbioError

  "Throw a DBIOError execption"

  [^String msg]

  (trap! DBIOError msg))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioScopeType

  "Scope a type id"

  [t]

  (keyword (str *ns* "/" t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeGetVendor

  "Try to detect the database vendor"

  [^String product]

  (let [fc #(embeds? %2 %1)
        lp (lcase product)]
    (condp fc lp
      "microsoft" :sqlserver
      "postgres" :postgresql
      "oracle" :oracle
      "mysql" :mysql
      "h2" :h2
      (mkDbioError (str "Unknown db product " product)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn concise

  "Extract key attributes from this object"

  [obj]

  (select-keys (meta obj) [:rowid :verid]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- fmtfkey ""

  [p1 p2]

  (keyword (str "fk_" (name p1) "_" (name p2))) )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn matchDbType

  "Ensure the database type is supported"

  [^String dbtype]

  (let [kw (keyword (lcase dbtype))]
    (when
      (some? (DBTYPES kw)) kw)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn matchJdbcUrl

  "From the jdbc url, get the database type"

  [^String url]

  (let [ss (StringUtils/split url \:) ]
    (when
      (> (alength ss) 1)
      (matchDbType (aget ss 1)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; data modelling
;;
(defonce JOINED-MODEL-MONIKER :czc.dbio.core/DBIOJoinedModel)
(defonce BASEMODEL-MONIKER :czc.dbio.core/DBIOBaseModel)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioModel

  "Define a generic database model"

  ([^String nm] (dbioModel (str *ns*) nm))

  ([^String nsp ^String nm]
   {:id (keyword (str nsp "/" nm))
    :table (ucase nm)
    :parent nil
    :abstract false
    :system false
    :mxm false
    :indexes {}
    :uniques {}
    :fields {}
    :assocs {} }) )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro defModelWithNSP

  "Define a data model (with namespace)"

  [nsp modelname & body]

  `(def ~modelname
     (-> (dbioModel ~nsp
                    ~(name modelname))
         ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro defModel

  "Define a data model"

  [modelname & body]

  `(def ~modelname
     (-> (dbioModel ~(name modelname))
         ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro defJoinedWithNSP

  "Define a joined data model with namespace"

  [nsp modelname lhs rhs]

  `(def ~modelname
      (-> (dbioModel ~nsp ~(name modelname))
          (withParentModel JOINED-MODEL-MONIKER)
          (withJoinedModel ~lhs ~rhs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro defJoined

  "Define a joined data model"

  [modelname lhs rhs]

  `(def ~modelname
      (-> (dbioModel ~(name modelname))
          (withParentModel JOINED-MODEL-MONIKER)
          (withJoinedModel ~lhs ~rhs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withParentModel

  "Give a parent to the model"

  [pojo par]

  {:pre [(map? pojo) (keyword? par)]}

  (assoc pojo :parent par))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withJoinedModel

  "A special model with 2 assocs,
   left hand side and right hand side"

  [pojo lhs rhs]

  {:pre [(map? pojo)
         (keyword? lhs) (keyword? rhs)]}

  (let [a1 {:kind :MXM :other lhs :fkey :lhs_rowid}
        a2 {:kind :MXM :other rhs :fkey :rhs_rowid}
        m2 (-> (:assocs pojo)
               (assoc :lhs a1)
               (assoc :rhs a2))]
    (-> pojo
        (assoc :assocs m2)
        (assoc :mxm true))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withTablename

  "Set the table name"

  [pojo tablename]

  {:pre [(map? pojo)]}

  (assoc pojo :table (ucase tablename)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withIndexes

  "Set indexes to the model"

  [pojo indices]

  {:pre [(map? pojo) (map? indices)]}

  ;;turn indices from array to set
  (let [m (reduce
            #(assoc %1
                    (first %2)
                    (into (ordered-set) (last %2)))
            {}
            indices)]
    (interject pojo :indexes #(merge (%2 %1) m))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withUniques

  "Set uniques to the model"

  [pojo uniqs]

  {:pre [(map? pojo) (map? uniqs)]}

  (let [m (reduce
            #(assoc %1
                    (first %2)
                    (into (ordered-set) (last %2)))
            {}
            uniqs)]
    (interject pojo :uniques #(merge (%2 %1) m))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- getDftFldObj

  "The base field structure"

  [fid]

  {:column (ucase (name fid))
   :domain :String
   :size 255
   :id (keyword fid)
   :assoc-key false
   :pkey false
   :null true
   :auto false
   :dft nil
   :updatable true
   :system false
   :index "" })

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withField

  "Create a new field"

  [pojo fid fdef]

  {:pre [(map? pojo) (map? fdef)]}

  (let [fd (merge (getDftFldObj fid) fdef)
        k (:id fd)]
    (interject pojo :fields #(assoc (%2 %1) k fd))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withFields

  "Create a batch of fields"

  [pojo flddefs]

  {:pre [(map? pojo) (coll? flddefs)]}

  (with-local-vars [rcmap pojo]
    (doseq [[k v] (seq flddefs)]
      (var-set rcmap (withField @rcmap k v)))
    @rcmap))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withAssoc

  "Set an association"

  [pojo aid adef]

  {:pre [(map? pojo) (map? adef)]}

  (let [ad (merge {:kind nil :other nil
                   :fkey nil :cascade false} adef)
        a2 (case (:kind ad)
             (:O2O :O2M)
             (assoc ad :fkey (fmtfkey (:id pojo) aid))
             (:M2M :MXM)
             ad
             ;;else
             (mkDbioError (str "Invalid assoc def " adef)))
        k (keyword aid)]
    (interject pojo :assocs #(assoc (%2 %1) k a2))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withAssocs

  "Set a batch of associations"

  [pojo assocs]

  {:pre [(map? pojo) (coll? assocs)]}

  (with-local-vars [rcmap pojo ]
    (doseq [[k v] (seq assocs)]
      (var-set rcmap (withAssoc @rcmap k v)))
    @rcmap))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn withAbstract

  "Set the model as abstract"

  [pojo]

  {:pre [(map? pojo)]}

  (assoc pojo :abstract true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- with-db-system

  "This is a built-in system level model"

  [pojo]

  (assoc pojo :system true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- nested-merge

  "Merge either a set or map"

  [src des]

  (cond
    (and (set? src)(set? des)) (cset/union src des)
    (and (map? src)(map? des)) (merge src des)
    :else des))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Defining the base model here
(defModelWithNSP _NSP DBIOBaseModel
  (with-db-system)
  (withAbstract)
  (withFields
    {:rowid {:column COL_ROWID :pkey true :domain :Long
             :auto true :system true :updatable false}
     :verid {:column COL_VERID :domain :Long :system true
             :dft [ 0 ] }
     :last-modify {:column COL_LASTCHANGED :domain :Timestamp
                   :system true :dft [""] }
     :created-on {:column COL_CREATED_ON :domain :Timestamp
                  :system true :dft [""] :updatable false}
     :created-by {:column COL_CREATED_BY :system true :domain :String } }))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defModelWithNSP _NSP DBIOJoinedModel
  (with-db-system)
  (withAbstract)
  (withFields
    {:lhs-typeid {:column COL_LHS_TYPEID }
     :lhs-oid {:column COL_LHS_ROWID :domain :Long :null false}
     :rhs-typeid {:column COL_RHS_TYPEID }
     :rhs-oid {:column COL_RHS_ROWID :domain :Long :null false} }) )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mkDbSchema

  "A schema holds the set of models"

  ^Schema
  [theModels]

  (reify

    Schema

    (getModels [_] theModels)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- resolve-assocs

  "Walk through all models, for each model, study its assocs.
  For o2o or o2m assocs, we need to artificially inject a new
  field/column into the model (foreign key)"

  ;; map of models
  [ms]

  (with-local-vars
    [fdef {:domain :Long :assoc-key true }
     rc (transient {})
     xs (transient {})]
    ;; create placeholder maps for each model,
    ;; to hold new fields from assocs
    (doseq [[k m] ms]
      (var-set rc (assoc! @rc k {} )))
    ;; as we find new assoc fields, add them to the placeholder maps
    (doseq [[k m]  ms]
      (doseq [[x s] (:assocs m) ]
        (case (:kind s)
          (:O2O :O2M)
          (let [fid (keyword (:fkey s))
                rhs (@rc (:other s))
                ft (merge (getDftFldObj fid) @fdef)]
            (var-set rc (assoc! @rc (:other s) (assoc rhs fid ft))))
          nil)))
    ;; now walk through all the placeholder maps and merge those new
    ;; fields to the actual models
    (let [tm (persistent! @rc)]
      (doseq [[k v] tm]
        (let [mcz (ms k)
              fs (:fields mcz)]
          (var-set xs (assoc! @xs
                              k
                              (assoc mcz :fields (merge fs v))))))
      (persistent! @xs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- resolve-parent

  "Ensure that all user defined models are
   all derived from the system base model"

  ;; map of models
  ;; model defn
  [mcz model]

  (let [par (:parent model)]
    (cond
      (keyword? par)
      (if (nil? (mcz par))
        (mkDbioError (str "Unknown parent model " par))
        model)

      (nil? par)
      (assoc model :parent BASEMODEL-MONIKER)

      :else
      (mkDbioError (str "Invalid parent " par)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Idea is walk through all models and ultimately
;; link it's root to base-model
(defn- resolve-parents ""

  ;; map of models
  [ms]

  (persistent!
    (reduce
      #(let [rc (resolve-parent ms (last %2))]
         (assoc! %1 (:id rc) rc))
      (transient {})
      (seq ms))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- mapize-models

  "Turn the list of models into a map of models,
   keyed by the model id"

  ;; list of models
  [ms]

  (persistent!
    (reduce #(assoc! %1 (:id %2) %2)
            (transient {})
            (seq ms))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- collect-db-xxx-filter ""

  [k a b]

  (cond
    (keyword? b)
    :keyword

    (map? b)
    :map

    :else
    (mkDbioError (str "Invalid arg " b))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmulti collectDbXXX collect-db-xxx-filter)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod collectDbXXX :keyword

  [kw cache model-id]

  (if-some [mcz (cache model-id)]
    (collectDbXXX kw cache mcz)
    (log/warn "Unknown database model id: %s" model-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod collectDbXXX :map

  [kw cache model-def]

  (if-some [par (:parent model-def)]
    (merge {} (collectDbXXX kw cache par)
              (kw model-def))
    (merge {} (kw model-def))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- colmap-fields

  "Create a map of fields keyed by the column name"

  [flds]

  (with-local-vars [sum (transient {})]
    (doseq [[k v] flds]
      (let [cn (ucase (str (:column v)))]
        (var-set sum (assoc! @sum cn v))))
    (persistent! @sum)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- meta-models

  "Inject extra meta-data properties into each model.  Each model will have
   its (complete) set of fields keyed by column nam or field id"

  [cache]

  (with-local-vars [sum (transient {})]
    (doseq [[k m] cache]
      (let [flds (collectDbXXX :fields cache m)
            cols (colmap-fields flds)]
        (var-set sum
                 (assoc! @sum k
                         (with-meta m
                                    {:columns cols
                                     :fields flds } ) ))))
    (persistent! @sum)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mkMetaCache

  "A cache storing meta-data for all models"

  ^MetaCache
  [^Schema schema]

  (let [ms (if (nil? schema)
             {}
             (mapize-models (.getModels schema)))
        m2 (if (empty? ms)
             {}
             (-> (assoc ms JOINED-MODEL-MONIKER DBIOJoinedModel)
                 (resolve-parents)
                 (resolve-assocs)
                 (assoc BASEMODEL-MONIKER DBIOBaseModel)
                 (meta-models)))]
    (reify

      MetaCache

      (getMetas [_] m2))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbgShowMetaCache ""

  ^String
  [^MetaCache mcache]

  (reduce #(addDelim! %1
                      "\n"
                      (writeEdnString {:TABLE (:table %2)
                                       :DEFN %2
                                       :META (meta %2)}))
          (StringBuilder.)
          (vals (.getMetas mcache))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- safeGetConn

  "Safely connect to database referred by this jdbc"

  ^Connection
  [^JDBCInfo jdbc]

  (let [user (.getUser jdbc)
        dv (.getDriver jdbc)
        url (.getUrl jdbc)
        d (if (hgl? url) (DriverManager/getDriver url))
        p (let [pps (Properties.)]
            (if (hgl? user)
              (doto pps
                (.put "password" (str (.getPwd jdbc)))
                (.put "user" user)
                (.put "username" user)))
            pps)]
    (when (nil? d) (mkDbioError (str "Can't load Jdbc Url: " url)))
    (when (and (hgl? dv)
               (not= (-> d (.getClass) (.getName)) dv))
      (log/warn "expected %s, loaded with driver: %s" dv (.getClass d)))
    (.connect d url p)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mkDbConnection

  "Connect to database referred by this jdbc"

  ^Connection
  [^JDBCInfo jdbc]

  (let [url (.getUrl jdbc)
        ^Connection
        conn (if (hgl? (.getUser jdbc))
               (safeGetConn jdbc)
               (DriverManager/getConnection url))]
    (when (nil? conn)
      (mkDbioError (str "Failed to create db connection: " url)))
    (doto conn
      (.setTransactionIsolation Connection/TRANSACTION_SERIALIZABLE))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn testConnection ""

  [jdbc]

  (tryc (.close (mkDbConnection jdbc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmulti resolveVendor "Find out the type of database" class)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod resolveVendor JDBCInfo

  [jdbc]

  (with-open [conn (mkDbConnection jdbc)]
    (resolveVendor conn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod resolveVendor Connection

  [^Connection conn]

  (let [md (.getMetaData conn) ]
    (-> {:id (maybeGetVendor (.getDatabaseProductName md)) }
        (assoc :version (.getDatabaseProductVersion md))
        (assoc :name (.getDatabaseProductName md))
        (assoc :quote-string (.getIdentifierQuoteString md))
        (assoc :url (.getURL md))
        (assoc :user (.getUserName md))
        (assoc :lcis (.storesLowerCaseIdentifiers md))
        (assoc :ucis (.storesUpperCaseIdentifiers md))
        (assoc :mcis (.storesMixedCaseIdentifiers md)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmulti tableExist?
  "Is this table defined in db?" (fn [a b] (class a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod tableExist? JDBCPool

  [^JDBCPool pool ^String table]

  (with-open [conn (.nextFree pool) ]
    (tableExist? conn table)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod tableExist? JDBCInfo

  [jdbc ^String table]

  (with-open [conn (mkDbConnection jdbc)]
    (tableExist? conn table)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod tableExist? Connection

  [^Connection conn ^String table]

  (with-local-vars [rc false]
    (log/debug "testing the existence of table: %s" table)
    (trylet!
      [mt (.getMetaData conn)
       tbl (cond
             (.storesUpperCaseIdentifiers mt)
             (ucase table)
             (.storesLowerCaseIdentifiers mt)
             (lcase table)
             :else table)]
      (with-open [res (.getColumns mt nil nil tbl nil)]
        (when (and (some? res)
                   (.next res))
          (var-set rc true))))
    @rc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmulti rowExist?
  "Is there any rows in the table?" (fn [a b] (class a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod rowExist? JDBCInfo

  [jdbc ^String table]

  (with-open [conn (mkDbConnection jdbc)]
    (rowExist? conn table)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod rowExist? Connection

  [^Connection conn ^String table]

  (with-local-vars [rc false]
    (trylet!
      [sql (str "SELECT COUNT(*) FROM  "
                (ucase table))]
      (with-open [stmt (.createStatement conn)
                  res (.executeQuery stmt sql)]
        (when (and (some? res)
                   (.next res))
          (var-set rc (> (.getInt res (int 1)) 0)))))
    @rc))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- load-columns

  "Read each column's metadata"

  [^DatabaseMetaData mt
   ^String catalog
   ^String schema ^String table]

  (with-local-vars
    [pkeys #{} cms {} ]
    (with-open [rs (.getPrimaryKeys mt
                                    catalog
                                    schema table) ]
      (loop [sum (transient #{})
             more (.next rs)]
        (if (not more)
          (var-set pkeys (persistent! sum))
          (recur
            (conj! sum (ucase (.getString rs (int 4))) )
            (.next rs)))))
    (with-open [rs (.getColumns mt
                                catalog
                                schema table "%")]
      (loop [sum (transient {})
             more (.next rs)]
        (if (not more)
          (var-set cms (persistent! sum))
          (let [opt (not= (.getInt rs (int 11))
                          DatabaseMetaData/columnNoNulls)
                cn (ucase (.getString rs (int 4)))
                ctype (.getInt rs (int 5))]
            (recur
              (assoc! sum (keyword cn)
                      {:column cn :sql-type ctype :null opt
                       :pkey (clojure.core/contains? @pkeys cn) })
              (.next rs))))))
    (with-meta @cms {:supportsGetGeneratedKeys
                     (.supportsGetGeneratedKeys mt)
                     :supportsTransactions
                     (.supportsTransactions mt) })))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn loadTableMeta

  "Fetch metadata of this table from db"

  [^Connection conn ^String table]

  (let [dbv (resolveVendor conn)
        mt (.getMetaData conn)
        catalog nil
        schema (if (= (:id dbv) :oracle) "%" nil)
        tbl (cond
              (.storesUpperCaseIdentifiers mt)
              (ucase table)
              (.storesLowerCaseIdentifiers mt)
              (lcase table)
              :else table) ]
    ;; not good, try mixed case... arrrrrrrrrrhhhhhhhhhhhhhh
    ;;rs = m.getTables( catalog, schema, "%", null)
    (load-columns mt catalog schema tbl)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- makePool ""

  ^JDBCPool
  [^JDBCInfo jdbc ^BoneCP impl]

  (let [dbv (resolveVendor jdbc)]
    (reify

      JDBCPool

      (shutdown [_]
        (log/debug "About to shut down the pool impl: %s" impl)
        (.shutdown impl))

      (dbUrl [_] (.getUrl jdbc))
      (vendor [_] dbv)

      (nextFree [_]
        (try
            (.getConnection impl)
          (catch Throwable e#
            (log/error e# "")
            (mkDbioError (str "No free connection"))))))))

      ;;Object
      ;;Clojure CLJ-1347
      ;;finalize won't work *correctly* in reified objects - document
      ;;(finalize [this]
        ;;(try!
          ;;(log/debug "DbPool finalize() called.")
          ;;(.shutdown this)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn mkDbPool

  "Create a db connection pool"

  [^JDBCInfo jdbc options]

  (let [options (or options {})
        dv (.getDriver jdbc)
        bcf (BoneCPConfig.) ]

    ;;(log/debug "URL: %s"  (.getUrl jdbc))
    ;;(log/debug "Driver: %s" dv)
    ;;(log/debug "Options: %s" options)

    (when (hgl? dv) (forname dv))
    (doto bcf
          (.setPartitionCount (Math/max 1 (nnz (:partitions options))))
          (.setLogStatementsEnabled (nbf (:debug options)))
          (.setPassword (str (.getPwd jdbc)))
          (.setJdbcUrl (.getUrl jdbc))
          (.setUsername (.getUser jdbc))
          (.setIdleMaxAgeInSeconds (* 60 60 2)) ;; 2 hrs
          (.setMaxConnectionsPerPartition (Math/max 1 (nnz (:max-conns options))))
          (.setMinConnectionsPerPartition (Math/max 1 (nnz (:min-conns options))))
          (.setPoolName (juid))
          (.setAcquireRetryDelayInMs 5000)
          (.setConnectionTimeoutInMs  (Math/max 5000 (nnz (:max-conn-wait options))))
          (.setDefaultAutoCommit false)
          ;;(.setConnectionHook (BoneCPHook.))
          (.setAcquireRetryAttempts 1))
    (log/debug "[bonecp]\n%s" (.toString bcf))
    (makePool jdbc (BoneCP. bcf))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- splitLines ""

  [^String lines]

  (with-local-vars
    [w (.length DDL_SEP)
     rc []
     s2 lines]
    (loop [sum (transient [])
           ddl lines
           pos (.indexOf ddl DDL_SEP)]
      (if (< pos 0)
        (do (var-set rc (persistent! sum))
            (var-set s2 (strim ddl)))
        (let [nl (strim (.substring ddl 0 pos))
              d2 (.substring ddl (+ pos @w))
              p2 (.indexOf d2 DDL_SEP) ]
          (recur (conj! sum nl) d2 p2))))
    (if (hgl? @s2)
      (conj @rc @s2)
      @rc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeOK ""

  [^String dbn ^Throwable e]

  (let [oracle (embeds? (str dbn) "oracle")
        ee (rootCause e)
        ec (when (instance? SQLException ee)
             (.getErrorCode ^SQLException ee)) ]
    (if (nil? ec)
      (throw e)
      (cond
        (and oracle (== 942 ec)
             (== 1418 ec)
             (== 2289 ec)(== 0 ec))
        true
        :else
        (throw e)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmulti uploadDdl "Upload DDL to DB" (fn [a b] (class a)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod uploadDdl JDBCPool

  [^JDBCPool pool ^String ddl]

  (with-open [conn (.nextFree pool)]
     (uploadDdl conn ddl)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod uploadDdl JDBCInfo

  [jdbc ^String ddl]

  (with-open [conn (mkDbConnection jdbc)]
     (uploadDdl conn ddl)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod uploadDdl Connection

  [^Connection conn ^String ddl]

  (log/debug "\n%s" ddl)
  (let [dbn (lcase (-> (.getMetaData conn)
                       (.getDatabaseProductName)))
        lines (splitLines ddl) ]
    (.setAutoCommit conn true)
    (doseq [^String line (seq lines)
            :let
            [ln (StringUtils/strip (strim line) ";")]
            :when (and (hgl? ln)
                       (not= (lcase ln) "go"))]
      (try
        (with-open [stmt (.createStatement conn)]
          (.executeUpdate stmt ln))
        (catch SQLException e#
          (maybeOK dbn e#))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioCreateObj

  "Creates a blank object of the given type
   model : keyword, the model type id"

  [model-id]

  {:pre [(keyword? model-id)]}

  (with-meta {} { :typeid model-id } ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioSetFlds

  "Set many field values"

  [pojo fld value & fvs]

  (apply assoc pojo fld value fvs))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioSetFld

  "Set many field values"

  [pojo fvs]

  {:pre [(map? fvs)]}

  (if-not (empty? fvs)
    (let [a (flatten (seq fvs))]
      (apply dbioSetFlds pojo (first a)(second a) (drop 2 a)))
    pojo))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioSetFld

  "Set value to a field"

  [pojo fld value]

  (assoc pojo fld value))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioClrFld

  "Remove a field"

  [pojo fld]

  (dissoc pojo fld))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioGetFld

  "Get value of a field"

  [pojo fld]

  (get pojo fld))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioGetAssoc

  "Get the assoc definition
   cache : meta cache
   mcz : the model
   id : assoc id"

  [cache model-def id]

  (when (some? model-def)
    (let [rc ((:assocs model-def) id)]
      (if (nil? rc)
        (dbioGetAssoc cache (cache (:parent model-def)) id)
        rc))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; handling assocs
(defn- dbio-get-o2x ""

  [ctx lhsObj]

  (let [^SQLr sqlr (:with ctx)
        mcache (.metas sqlr)
        mcz (mcache (getTypeId lhsObj))
        ac (dbioGetAssoc mcache mcz (:as ctx))
        rt (:cast ctx)
        fid (:fkey ac)
        fv (:rowid (meta lhsObj))]
    (if (nil? ac)
      (mkDbioError "Unknown assoc " (:as ctx))
      [ sqlr (or rt (:other ac)) {fid fv} ])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- dbio-set-o2x ""

  [ctx lhsObj rhsObj]

  (let [^SQLr sqlr (:with ctx)
        mcache (.metas sqlr)
        mcz (mcache (getTypeId lhsObj))
        ac (dbioGetAssoc mcache mcz (:as ctx))
        fv (:rowid (meta lhsObj))
        fid (:fkey ac) ]
    (when (nil? ac) (mkDbioError "Unknown assoc " (:as ctx)))
    (let [x (-> (dbioCreateObj (getTypeId rhsObj))
                (dbioSetFld fid fv)
                (vary-meta mergeMeta (concise rhsObj)))
          y (.update sqlr x)]
      [ lhsObj (merge y (dissoc rhsObj fid)) ])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioGetO2M

  "One to many assocs"

  [ctx lhsObj]

  (let [[sql rt pms] (dbio-get-o2x ctx lhsObj)]
    (when (some? rt)
      (.findSome ^SQLr sql rt pms))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioSetO2M ""

  [ctx lhsObj rhsObj]

  (dbio-set-o2x ctx lhsObj rhsObj))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioAddO2M ""

  [ctx lhsObj rhsObjs]

  (with-local-vars [rc (transient []) ]
    (doseq [r rhsObjs]
      (var-set rc
               (conj! @rc
                      (last (dbio-set-o2x ctx lhsObj r)))))
    [ lhsObj (persistent! @rc) ]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioClrO2M ""

  [ctx lhsObj]

  (let [^SQLr sqlr (:with ctx)
        mcache (.metas sqlr)
        mcz (mcache (getTypeId lhsObj))
        ac (dbioGetAssoc mcache mcz (:as ctx))
        fv (:rowid (meta lhsObj))
        rt (:cast ctx)
        rp (or rt (:other ac))
        fid (:fkey ac) ]
    (when (nil? ac)(mkDbioError "Unknown assoc " (:as ctx)))
    (if (:cascade ac)
      (.exec sqlr (str "delete from "
                       (gtable (mcache rp))
                       " where " (ese fid) " = ?") [fv])
      (.exec sqlr (str "update "
                       (gtable (mcache rp))
                       " set " (ese fid) " = NULL "
                       " where " (ese fid) " = ?") [fv]))
    lhsObj))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioGetO2O

  "One to one assocs"

  [ctx lhsObj]

  (let [[sql rt pms] (dbio-get-o2x ctx lhsObj) ]
    (.findOne ^SQLr sql rt pms)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioSetO2O ""

  [ctx lhsObj rhsObj]

  (dbio-set-o2x ctx lhsObj rhsObj))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioClrO2O ""

  [ctx lhsObj]

  (let [^SQLr sqlr (:with ctx)
        mcache (.metas sqlr)
        mcz (mcache (getTypeId lhsObj))
        ac (dbioGetAssoc mcache mcz (:as ctx))
        fv (:rowid (meta lhsObj))
        rt (:cast ctx)
        fid (:fkey ac) ]
    (when (nil? ac) (mkDbioError "Unknown assoc " (:as ctx)))
    (let [y (.findOne sqlr
                      (or rt (:other ac))
                      { fid fv } ) ]
      (when (some? y)
        (let [x (vary-meta (-> (dbioCreateObj (getTypeId y))
                               (dbioSetFld fid nil))
                            mergeMeta (meta y))]
          (if (:cascade ac)
            (.delete sqlr x)
            (.update sqlr x)))))
    lhsObj))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioSetM2M

  "Many to many assocs"

  [ctx lhsObj rhsObj]

  (let [^SQLr sqlr (:with ctx)
        mcache (.metas sqlr)
        rv (:rowid (meta rhsObj))
        lv (:rowid (meta lhsObj))
        lid (getTypeId lhsObj)
        rid (getTypeId rhsObj)
        mcz (mcache lid)
        ac (dbioGetAssoc mcache mcz (:as ctx))
        mm (mcache (:joined ac))
        rl (:other (:rhs (:assocs mm)))
        ml (:other (:lhs (:assocs mm)))
        x (dbioCreateObj (:id mm))
        y  (if (= ml lid)
              (-> x
                  (dbioSetFld :lhs-typeid (stripNSPath lid))
                  (dbioSetFld :lhs-oid lv)
                  (dbioSetFld :rhs-typeid (stripNSPath rid))
                  (dbioSetFld :rhs-oid rv))
              (-> x
                  (dbioSetFld :lhs-typeid (stripNSPath rid))
                  (dbioSetFld :lhs-oid rv)
                  (dbioSetFld :rhs-typeid (stripNSPath lid))
                  (dbioSetFld :rhs-oid lv))) ]
    (.insert sqlr y)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioClrM2M

  ([ctx lhsObj] (dbioClrM2M ctx lhsObj nil))

  ([ctx lhsObj rhsObj]
    (let [^SQLr sqlr (:with ctx)
          mcache (.metas sqlr)
          rv (:rowid (meta rhsObj))
          lv (:rowid (meta lhsObj))
          lid (getTypeId lhsObj)
          rid (getTypeId rhsObj)
          mcz (mcache lid)
          ac (dbioGetAssoc mcache mcz (:as ctx))
          mm (mcache (:joined ac))
          flds (:fields (meta mm))
          rl (:other (:rhs (:assocs mm)))
          ml (:other (:lhs (:assocs mm)))
          [x y a b]
          (if (= ml lid)
              [ (:column (:lhs-oid flds)) (:column (:lhs-typeid flds))
                (:column (:rhs-oid flds)) (:column (:rhs-typeid flds)) ]
              [ (:column (:rhs-oid flds)) (:column (:rhs-typeid flds))
                (:column (:lhs-oid flds)) (:column (:lhs-typeid flds)) ]) ]
      (when (nil? ac) (mkDbioError "Unkown assoc " (:as ctx)))
      (if (nil? rhsObj)
        (.exec sqlr
               (str "delete from "
                    (gtable mm)
                    " where " (ese x) " =? and " (ese y) " =?")
               [ lv (stripNSPath lid) ] )
        (.exec sqlr
               (str "delete from "
                    (gtable mm)
                    " where " (ese x) " =? and " (ese y) " =? and "
                    (ese a)
                    " =? and "
                    (ese b)
                    " =?" )
               [ lv (stripNSPath lid) rv (stripNSPath rid) ])) )))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn dbioGetM2M ""

  [ctx lhsObj]

  (let [^SQLr sqlr (:with ctx)
        mcache (.metas sqlr)
        lv (:rowid (meta lhsObj))
        lid (getTypeId lhsObj)
        eseRES (ese "RES")
        eseMM (ese "MM")
        mcz (mcache lid)
        ac (dbioGetAssoc mcache mcz (:as ctx))
        mm (mcache (:joined ac))
        flds (:fields (meta mm))
        rl (:other (:rhs (:assocs mm)))
        ml (:other (:lhs (:assocs mm)))
        [x y z k a t]
        (if (= ml lid)
          [:lhs-typeid :rhs-typeid :lhs-oid :rhs-oid ml rl]
          [:rhs-typeid :lhs-typeid :rhs-oid :lhs-oid rl ml] ) ]
    (when (nil? ac) (mkDbioError "Unknown assoc " (:as ctx)))
    (.select sqlr
             t
             (str "select distinct "
                  eseRES
                  ".* from "
                  (gtable (get mcache t))
                  " " eseRES " join " (gtable mm) " " eseMM " on "
                  (str eseMM "." (ese (:column (x flds))))
                  "=? and "
                  (str eseMM "." (ese (:column (y flds))))
                  "=? and "
                  (str eseMM "." (ese (:column (z flds))))
                  "=? and "
                  (str eseMM "." (ese (:column (k flds))))
                  " = " (str eseRES "." (ese (:column (:rowid flds)))))
                  [ (stripNSPath a) (stripNSPath t) lv ])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


