(ns gremllm.renderer.actions.settings-test
  (:require [cljs.test :refer [deftest is testing]]
            [gremllm.renderer.actions.settings :as settings]))

(deftest save-key-test
  (testing "returns empty effects when input is empty"
    (let [state {:sensitive {:api-key-inputs {:anthropic ""}}}
          effects (settings/save-key state :anthropic)]
      (is (= [] effects)))))

;; Note: save-key and remove-key create browser promises (window.electronAPI) which
;; aren't available in Node test environment. Promise creation is tested via integration
;; tests. Unit tests focus on domain behavior: empty key validation and success handlers.

(deftest save-success-test
  (testing "clears input for provider and requests system info reload"
    (let [effects (settings/save-success {} nil :anthropic)
          effect-names (map first effects)]
      (is (= 3 (count effects)))
      (is (some #{:effects/save} effect-names))
      (is (some #{:system.actions/request-info} effect-names))
      (is (some #{:ui.actions/hide-settings} effect-names)))))

(deftest remove-success-test
  (testing "requests system info reload and hides settings"
    (let [effects (settings/remove-success {} nil :anthropic)]
      (is (= 2 (count effects)))
      (is (= :system.actions/request-info (ffirst effects)))
      (is (= :ui.actions/hide-settings (first (second effects)))))))
