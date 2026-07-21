(ns karcarthy.contract
  "Contracts shared by Agents and Tools."
  (:require [karcarthy.core :as core]))

(def valid? core/contract-valid?)
(def explain core/explain-contract)
(def json-schema core/contract->json-schema)
