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

  czlab.dbddl.sqlserver

  (:require
    [czlab.xlib.logging :as log])

  (:use [czlab.dbddl.drivers]
        [czlab.dbio.core]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; SQLServer
(defmethod getDoubleKwd SQLServer [db] "FLOAT(53)")
(defmethod getFloatKwd SQLServer [db] "FLOAT(53)")
(defmethod getBlobKwd SQLServer [db] "IMAGE")
(defmethod getTSKwd SQLServer [db] "DATETIME")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod genAutoInteger SQLServer

  [db table fld]

  (str (getPad db)
       (genCol fld)
       " "
       (getIntKwd db)
       (if (:pkey fld)
         " IDENTITY (1,1) "
         " AUTOINCREMENT ")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod genAutoLong SQLServer

  [db table fld]

  (str (getPad db)
       (genCol fld)
       " "
       (getLongKwd db)
       (if (:pkey fld)
         " IDENTITY (1,1) "
         " AUTOINCREMENT ")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmethod genDrop SQLServer

  [db table]

  (str "IF EXISTS (SELECT * FROM dbo.sysobjects WHERE id=object_id('"
       table
       "')) DROP TABLE "
       table
       (genExec db) "\n\n"))

;;(println (getDDL (reifyMetaCache testschema) (SQLServer.) ))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


