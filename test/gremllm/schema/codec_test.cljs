(ns gremllm.schema.codec-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.schema.codec :as codec]))

(deftest test-acp-read-display-label
  (testing "returns 'Read — filename (N lines)' when tool-response meta present"
    (is (= "Read — document.md (37 lines)"
           (codec/acp-read-display-label
             {:session-update :tool-call-update
              :tool-call-id "toolu_01Ext"
              :meta {:claude-code {:tool-name "Read"
                                   :tool-response {:file {:filePath "/path/to/document.md"
                                                          :totalLines 37
                                                          :numLines 37
                                                          :startLine 1}
                                                   :type "text"}}}}))))

  (testing "returns nil when tool-response meta is absent"
    (is (nil? (codec/acp-read-display-label
                {:session-update :tool-call-update
                 :tool-call-id "toolu_01Ext"
                 :status "completed"
                 :meta {:claude-code {:tool-name "Read"}}})))))
