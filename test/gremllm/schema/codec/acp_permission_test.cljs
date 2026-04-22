(ns gremllm.schema.codec.acp-permission-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.schema.codec :as codec]
            [gremllm.schema.codec.acp-permission :as acp-permission]))

;; Helpers for constructing coerced fixtures.

(defn- coerce-session-update [js-data]
  (codec/acp-session-update-from-js js-data))

(defn- coerce-permission-req [js-data]
  (codec/acp-permission-request-from-js js-data))

(defn- full-options-js []
  #js [#js {:optionId "allow-always"  :kind "allow_always"  :name "allow_always"}
       #js {:optionId "allow-once"    :kind "allow_once"    :name "allow_once"}
       #js {:optionId "reject-once"   :kind "reject_once"   :name "reject_once"}
       #js {:optionId "reject-always" :kind "reject_always" :name "reject_always"}])

(defn- tool-call-update-js [tool-call-id tool-name]
  #js {:sessionId "s-1"
       :update    #js {:sessionUpdate "tool_call"
                       :toolCallId    tool-call-id
                       :title         "Edit"
                       :kind          "edit"
                       :status        "running"
                       :rawInput      #js {}
                       :content       #js []
                       :_meta         #js {:claudeCode #js {:toolName tool-name}}}})

(deftest test-permission-requested-tool-name
  (testing "returns :tool-name when present"
    (is (= "mcp__acp__Edit"
           (acp-permission/requested-tool-name {:tool-name "mcp__acp__Edit"}))))
  (testing "returns nil when :tool-name is absent"
    (is (nil? (acp-permission/requested-tool-name {}))))
  (testing "returns nil when :tool-name is empty"
    (is (nil? (acp-permission/requested-tool-name {:tool-name ""})))))

(deftest test-remember-and-enrich-tool-name
  (let [session-update (coerce-session-update (tool-call-update-js "toolu_01" "mcp__acp__Edit"))
        tool-names     (acp-permission/remember-tool-name {} session-update)]
    (testing "remembers tool name from session update"
      (is (= "mcp__acp__Edit" (get tool-names "toolu_01"))))
    (let [perm-req (coerce-permission-req
                     #js {:sessionId "session-1"
                          :toolCall  #js {:toolCallId "toolu_01"
                                          :kind       "edit"
                                          :title      "Edit `/tmp/test.md`"
                                          :rawInput   #js {:file_path "/tmp/test.md"}
                                          :locations  #js []}
                          :options   #js []})
          enriched (acp-permission/enrich-permission-params tool-names perm-req)]
      (testing "enriches permission params with tracked tool name"
        (is (= "mcp__acp__Edit" (get-in enriched [:tool-call :tool-name])))))))

(deftest test-enrich-without-tracked-tool-name
  (let [perm-req (coerce-permission-req
                   #js {:sessionId "session-1"
                        :toolCall  #js {:toolCallId "toolu_missing"
                                        :kind       "edit"
                                        :title      "Edit `/tmp/test.md`"
                                        :rawInput   #js {:file_path "/tmp/test.md"}
                                        :locations  #js []}
                        :options   #js []})
        enriched (acp-permission/enrich-permission-params {} perm-req)]
    (testing "leaves tool-name absent when no tracked name exists"
      (is (nil? (get-in enriched [:tool-call :tool-name]))))))

(deftest test-permission-resolver-policy
  (let [path-mod     (js/require "path")
        cwd          (.resolve path-mod (.cwd js/process) "resources")
        file-in-cwd  (.resolve path-mod cwd "gremllm-launch-log.md")
        file-out     (.resolve path-mod (.cwd js/process) "README.md")
        options      (full-options-js)
        option-id    (fn [result] (get-in result [:outcome :option-id]))
        make-req     (fn [kind tool-name raw-input]
                       (coerce-permission-req
                         #js {:sessionId "session-known"
                              :toolCall  #js {:toolCallId "tc"
                                              :toolName   tool-name
                                              :kind       kind
                                              :title      "op"
                                              :rawInput   raw-input
                                              :locations  #js []}
                              :options   options}))]
    (testing "read tool call is allowed regardless of path"
      (is (= "allow-always"
             (option-id (acp-permission/resolve-permission
                          (make-req "read" "mcp__acp__Read" #js {:path file-in-cwd})
                          cwd)))))
    (testing "edit within cwd is allowed"
      (is (= "allow-once"
             (option-id (acp-permission/resolve-permission
                          (make-req "edit" "mcp__acp__Edit" #js {:path file-in-cwd})
                          cwd)))))
    (testing "edit outside cwd is rejected"
      (is (= "reject-once"
             (option-id (acp-permission/resolve-permission
                          (make-req "edit" "mcp__acp__Edit" #js {:file_path file-out})
                          cwd)))))
    (testing "edit without path metadata is rejected"
      (is (= "reject-once"
             (option-id (acp-permission/resolve-permission
                          (make-req "edit" "mcp__acp__Edit" #js {:replace_all false})
                          cwd)))))
    (testing "edit with nil session-cwd is rejected"
      (is (= "reject-once"
             (option-id (acp-permission/resolve-permission
                          (make-req "edit" "mcp__acp__Edit" #js {:path file-in-cwd})
                          nil)))))
    (testing "empty options yields cancelled"
      (is (= "cancelled"
             (get-in (acp-permission/resolve-permission
                       (coerce-permission-req
                         #js {:sessionId "session-known"
                              :toolCall  #js {:toolCallId "tc"
                                              :kind       "read"
                                              :title      "op"
                                              :rawInput   #js {:path file-in-cwd}
                                              :locations  #js []}
                              :options   #js []})
                       cwd)
                     [:outcome :outcome]))))))
