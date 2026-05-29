(ns karcarthy.session-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.session :as sess]))

;; A runner that records the :resume it was called with and always reports the
;; same session id, so we can assert the conversation threads it forward.
(defn- session-mock [resumes]
  (reify k/Runner
    (-run [_ agent prompt opts]
      (swap! resumes conj (:resume opts))
      (k/result {:agent      (:name agent)
                 :text       (str "[" (count @resumes) "] " prompt)
                 :session-id "S"}))))

(deftest converse-threads-session
  (testing "first turn has no resume; later turns resume the prior session"
    (let [resumes (atom [])
          rs      (sess/converse (session-mock resumes)
                                 (k/agent "a" "i")
                                 ["one" "two" "three"])]
      (is (= 3 (count rs)))
      (is (= [nil "S" "S"] @resumes))
      (is (= ["[1] one" "[2] two" "[3] three"] (map :text rs))))))

(deftest converse-empty
  (testing "no prompts yields no results"
    (is (= [] (sess/converse (session-mock (atom [])) (k/agent "a" "i") [])))))
