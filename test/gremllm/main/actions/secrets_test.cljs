(ns gremllm.main.actions.secrets-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.actions.secrets :as secrets]))

(deftest test-secrets-data-structure
  (testing "can create empty secrets structure"
    (is (= {} (secrets/create-empty-secrets))))

  (testing "can add encrypted value to secrets"
    (let [secrets-map (secrets/add-secret {} :api-key "encrypted-base64-value")]
      (is (= {:api-key "encrypted-base64-value"} secrets-map))))

  (testing "can remove secret from secrets"
    (let [secrets-map {:api-key "encrypted-value" :other-key "other-value"}
          updated (secrets/remove-secret secrets-map :api-key)]
      (is (= {:other-key "other-value"} updated)))))
