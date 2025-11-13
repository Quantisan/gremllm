(ns gremllm.main.effects.ipc-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.main.effects.ipc :as ipc]
            [gremllm.test-utils :refer [with-console-error-silenced]]))

;; Mock Electron's event.sender.send to capture what gets sent
(defn mock-event [send-fn]
  #js {:sender #js {:send send-fn}})

(deftest test-reply
  (testing "constructs correct success channel"
    ;; Each request has a unique ID so multiple requests don't collide.
    ;; The success channel must include this ID.
    (let [sent (atom nil)
          ctx {:dispatch-data {:ipc-event (mock-event #(reset! sent [%1 %2]))
                               :ipc-correlation-id "123"
                               :channel "chat/send-message"}}]

      (ipc/reply ctx nil "result")

      (is (= ["chat/send-message-success-123" "result"] @sent))))

  (testing "no-op when ipc-event missing"
    ;; Not all actions come from IPC. Don't crash if there's no event to reply to.
    (let [sent (atom nil)
          ctx {:dispatch-data {:ipc-correlation-id "123"
                               :channel "chat/send-message"}}]

      (ipc/reply ctx nil "result")

      (is (nil? @sent)))))

(deftest test-reply-error
  (testing "constructs correct error channel"
    ;; Errors use a different channel pattern so the renderer can handle them separately
    (with-console-error-silenced
      (let [sent (atom nil)
            ctx {:dispatch-data {:ipc-event (mock-event #(reset! sent [%1 %2]))
                                 :ipc-correlation-id "456"
                                 :channel "topic/save"}}]

        (ipc/reply-error ctx nil (js/Error. "Save failed"))

        (is (= ["topic/save-error-456" "Save failed"] @sent)))))

  (testing "handles non-Error objects"
    ;; Errors come in many forms (Error objects, strings, maps).
    ;; Convert them all to strings so the renderer gets something useful.
    (with-console-error-silenced
      (let [sent (atom nil)
            ctx {:dispatch-data {:ipc-event (mock-event #(reset! sent [%1 %2]))
                                 :ipc-correlation-id "789"
                                 :channel "topic/load"}}]

        ;; String error
        (ipc/reply-error ctx nil "String error")
        (is (= "String error" (second @sent)))

        ;; ClojureScript map - stringify it instead of sending [object Object]
        (ipc/reply-error ctx nil {:type "custom"})
        (is (= "{:type \"custom\"}" (second @sent)))))))

(deftest test-promise->reply
  (testing "dispatches reply on success"
    (let [dispatched (atom nil)
          ctx {:dispatch #(reset! dispatched %)}
          promise (js/Promise.resolve {:status "ok"})]
      
      (ipc/promise->reply ctx nil promise)
      
      ;; Wait for promise to resolve
      (js/setTimeout 
        #(let [[action] @dispatched
               [action-type js-obj] action]
           (is (= :ipc.effects/reply action-type))
           (is (= "ok" (.-status js-obj))))
        10)))
  
  (testing "dispatches reply-error on failure"
    (let [dispatched (atom nil)
          ctx {:dispatch #(reset! dispatched %)}
          error (js/Error. "Failed")
          promise (js/Promise.reject error)]
      
      (ipc/promise->reply ctx nil promise)
      
      ;; Wait for promise to reject
      (js/setTimeout
        #(let [[action] @dispatched
               [action-type err] action]
           (is (= :ipc.effects/reply-error action-type))
           (is (= error err)))
        10))))
