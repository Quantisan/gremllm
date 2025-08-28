(ns gremllm.main.menu
   (:require ["electron/main" :refer [app Menu]]
             [nexus.registry :as nxr]))

(defn create-menu [store]
  (let [is-mac (= (.-platform js/process) "darwin")
        template [(when is-mac
                    {:label (.getName app)
                     :submenu [{:role "about"}
                               {:type "separator"}
                               {:label "Settings..."
                                :accelerator "Cmd+,"
                                :click #(nxr/dispatch store {} [[:effects/trigger-settings-in-renderer]])}
                               {:type "separator"}
                               {:role "services"
                                :submenu []}
                               {:type "separator"}
                               {:role "hide"}
                               {:role "hideOthers"}
                               {:role "unhide"}
                               {:type "separator"}
                               {:role "quit"}]})
                  ;; File menu
                  {:label "File"
                   :submenu (filter identity
                                    [{:label "Save"
                                      :accelerator (if is-mac "Cmd+S" "Ctrl+S")
                                      :click #(nxr/dispatch store {} [[:effects/trigger-save-in-renderer]])}

                                     ;; TODO: Open ...

                                     (when-not is-mac
                                       {:type "separator"})
                                     (when-not is-mac
                                       {:label "Settings..."
                                        :accelerator "Ctrl+,"
                                        :click #(nxr/dispatch store {} [[:effects/trigger-settings-in-renderer]])})
                                     {:type "separator"}
                                     (if is-mac
                                       {:role "close"}
                                       {:role "quit"})])}
                  ;; Edit menu
                  {:label "Edit"
                   :submenu (filter identity
                                    [{:role "undo"}
                                     {:role "redo"}
                                     {:type "separator"}
                                     {:role "cut"}
                                     {:role "copy"}
                                     {:role "paste"}
                                     (when is-mac
                                       {:role "pasteAndMatchStyle"})
                                     {:role "delete"}
                                     {:role "selectAll"}
                                     (when is-mac
                                       {:type "separator"})
                                     (when is-mac
                                       {:label "Speech"
                                        :submenu [{:role "startSpeaking"}
                                                  {:role "stopSpeaking"}]})])}
                  ;; View menu
                  {:label "View"
                   :submenu [{:role "reload"}
                             {:role "forceReload"}
                             {:role "toggleDevTools"}
                             {:type "separator"}
                             {:role "resetZoom"}
                             {:role "zoomIn"}
                             {:role "zoomOut"}
                             {:type "separator"}
                             {:role "togglefullscreen"}]}
                  ;; Window menu
                  {:label "Window"
                   :submenu (filter identity
                                    (concat
                                     [{:role "minimize"}
                                      (when is-mac {:role "zoom"})]
                                     (if is-mac
                                       [{:type "separator"}
                                        {:role "front"}
                                        {:type "separator"}
                                        {:role "window"}]
                                       [{:role "close"}])))}
                  ;; Help menu
                  {:role "help"
                   :submenu [{:label "Learn More"
                              :click (fn []
                                       (-> (js/require "electron")
                                           .-shell
                                           (.openExternal "https://electronjs.org")))}]}]
        ;; Filter out nil values from top-level template
        filtered-template (filter identity template)
        ;; Convert to JS
        js-template (clj->js filtered-template)]
    (let [menu (.buildFromTemplate Menu js-template)]
      (.setApplicationMenu Menu menu))))

;; Design Goals:
;; 1. Declarative - menus as data
;; 2. Platform-aware - handle mac/windows/linux differences elegantly
;; 3. Action-based - integrate with Nexus for all behaviors
;; 4. Minimal - no unnecessary abstractions

#_
(def menu-template
  "The complete menu structure as data.
   Platform-specific items use when conditionals.
   Actions are Nexus action vectors."
  (let [is-mac (= :mac platform)] ; or however you detect platform
    [(when is-mac
       {:label :app-name  ; special keyword for dynamic app name
        :items [{:role "about"}
                :---  ; separator
                {:role "services"}
                :---
                {:role "hide"}
                {:role "hideOthers"}
                {:role "unhide"}
                :---
                {:role "quit"}]})

     {:label "File"
      :items [{:label "New Conversation"
               :accelerator "CmdOrCtrl+N"
               :action [:conversation/new]}
              {:label "Save..."
               :accelerator "CmdOrCtrl+S"
               :action [:conversation/save]}
              :---
              (if is-mac
                {:role "close"}
                {:role "quit"})]}

     {:label "Edit"
      :items [{:role "undo"}
              {:role "redo"}
              :---
              {:role "cut"}
              {:role "copy"}
              {:role "paste"}
              (when is-mac {:role "pasteAndMatchStyle"})
              {:role "delete"}
              {:role "selectAll"}]}

     {:label "View"
      :items [{:role "reload"}
              {:role "toggleDevTools"}
              :---
              {:role "resetZoom"}
              {:role "zoomIn"}
              {:role "zoomOut"}
              :---
              {:role "togglefullscreen"}]}

     {:label "Window"
      :items [{:role "minimize"}
              (when is-mac {:role "zoom"})
              (when is-mac :---)
              (when is-mac {:role "front"})
              (when-not is-mac {:role "close"})]}

     {:role "help"  ; can set role at menu level
      :items [{:label "Documentation"
               :action [:help/open-docs]}
              (when-not is-mac :---)
              (when-not is-mac {:role "about"})]}]))

;; Design Notes:
;;
;; 1. Platform handling:
;;    - {:when :mac ...} - only on macOS
;;    - {:when :not-mac ...} - only on Windows/Linux
;;    - No :when means all platforms
;;
;; 2. Actions:
;;    - All custom behaviors go through Nexus actions
;;    - {:action [:conversation/save]} dispatches that action
;;    - Keeps menu definition pure data
;;
;; 3. Electron roles:
;;    - {:role "copy"} uses Electron's built-in behavior
;;    - Most standard menu items have roles
;;
;; 4. Special values:
;;    - :app-name - replaced with actual app name
;;    - :--- - shorthand for {:type "separator"}
;;
;; 5. Accelerators:
;;    - Use Electron's format: "CmdOrCtrl+S"
;;    - Automatically handles platform differences
;;
;; Future considerations:
;; - Dynamic menu items (recent files, open windows)
;; - Checkbox/radio items for settings
;; - Enabled/disabled state based on app state
;; - Context menus using same format
