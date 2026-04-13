(ns gremllm.renderer.actions.document-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.document :as document]
            [gremllm.renderer.state.document :as document-state]
            [gremllm.renderer.state.topic :as topic-state]))

(deftest set-content-test
  (let [state {:topics {"t1" {:id "t1" :staged-selections [{:id "a"}]}
                        "t2" {:id "t2" :staged-selections [{:id "b"}]}}
               :excerpt {:captured {:text "Dispatch"}
                         :anchor {:panel-scroll-top 20}
                         :locator-debug {:block-index 1}}}
        effects (document/set-content state "# Replaced")]
    (testing "saves the new content first"
      (is (= [:effects/save document-state/content-path "# Replaced"]
             (first effects))))

    (testing "clears staged selections for every topic"
      (is (some #{[:effects/save (topic-state/staged-selections-path "t1") []]} effects))
      (is (some #{[:effects/save (topic-state/staged-selections-path "t2") []]} effects)))

    (testing "dismisses live capture state"
      (is (some #{[:excerpt.actions/dismiss-popover]} effects)))))
