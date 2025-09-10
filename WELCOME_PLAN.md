# High-Level Plan

Domain Modeling (Modelarity)

The workspace is now a prerequisite for the app to function, not a background detail. This reflects our Vision: "Workspace as Repository" - you must open a
project before you can work in it, just like opening a folder in an IDE.

State Architecture (Strict FCIS)

Add explicit workspace state to the renderer:

 • :workspace/loaded? - boolean flag
 • :workspace/path - current workspace path (optional, for display)

When loaded? is false, show a welcome/picker screen. When true, show the normal chat UI.

User Flow (Pragmatic Evolution)

 1 App starts → No workspace loaded → Show welcome screen
 2 User clicks "Open Workspace" → Triggers existing :menu.actions/open-folder flow
 3 Workspace loads → State transitions → Chat UI appears

Implementation Approach

Phase 1: State & UI Changes

 • Modify :workspace.actions/bootstrap to NOT auto-load default workspace
 • Add workspace loaded state to renderer
 • Create simple welcome UI component that reuses the open-folder action
 • Modify main UI to conditionally render based on workspace state

Phase 2: Clean Up

 • Remove hardcoded "default" workspace path from io.cljs
 • Remove auto-create-new-topic logic from empty workspace load

Why This Approach?

 1 Minimal Complexity: Reuses existing open-folder mechanism, no new IPC channels
 2 Strict FCIS: UI state (workspace loaded) stays in renderer, effects stay in main process
 3 Modelarity: "No workspace" becomes an explicit, first-class state in the domain
 4 Boy Scout Rule: Removes the temporary "default" workspace hack

The key insight: We're not adding a "workspace picker feature" - we're making the existing workspace concept explicit in the user experience. The code already
treats workspaces as fundamental; now the UI will too.

# Implementation Idea

1. Create src/gremllm/renderer/state/workspace.cljs


(ns gremllm.renderer.state.workspace)

(def loaded-path [:workspace :loaded?])
(def workspace-path-path [:workspace :path])

(defn loaded? [state]
  (get-in state loaded-path false))

(defn workspace-path [state]
  (get-in state workspace-path-path))


2. Create src/gremllm/renderer/ui/welcome.cljs


(ns gremllm.renderer.ui.welcome)

(defn render-welcome []
  [:div {:class "flex flex-col items-center justify-center h-screen bg-gray-50"}
   [:div {:class "text-center space-y-6 p-8 max-w-md"}
    [:h1 {:class "text-4xl font-bold text-gray-800"} "Welcome to Gremllm"]
    [:p {:class "text-lg text-gray-600"}
     "Open a workspace folder to begin organizing your conversations"]
    [:button {:class "px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700
                      transition-colors font-medium text-lg"
              :on {:click [[:workspace.actions/open-folder]]}}
     "Open Workspace Folder"]
    [:p {:class "text-sm text-gray-500 mt-4"}
     "A workspace is like a project folder that contains all your related topics and conversations"]]])


3. Modify src/gremllm/renderer/actions/workspace.cljs

Replace the bootstrap function:


(defn bootstrap [_state]
  ;; No longer auto-load. User must explicitly open a workspace.
  [])


Modify populate-topics to also set workspace loaded state:


(defn populate-topics [_state workspace-topics-js]
  (let [workspace-topics-clj (js->clj workspace-topics-js :keywordize-keys true)
        {:keys [topics active-id]} (import-workspace-topics workspace-topics-clj)]

    ;; Mark workspace as loaded regardless of whether it has topics
    (concat
      [[:effects/save [:workspace :loaded?] true]]
      (if (seq topics)
        [[:effects/save topic-state/topics-path topics]
         [:effects/save topic-state/active-topic-id-path active-id]]
        [[:topic.actions/start-new]]))))


Add a new action for the open folder button:


(defn open-folder [_state]
  ;; Send command to main process to show folder picker
  [[:workspace.effects/request-open-folder]])


Add the effect registration at the bottom of the namespace:


(nxr/register-effect! :workspace.effects/request-open-folder
  (fn [_ _]
    ;; Send IPC to main process to trigger the folder picker
    (js/window.electronAPI.sendMessage
      #js {:type "workspace:open-folder-request"})))


4. Modify src/gremllm/renderer/ui.cljs

Add the require for the new namespaces:


[gremllm.renderer.state.workspace :as workspace-state]
[gremllm.renderer.ui.welcome :as welcome-ui]


Modify the render-app function:


(defn render-app [state]
  (if (workspace-state/loaded? state)
    ;; Existing chat UI code
    (let [has-api-key?          (system-state/has-anthropic-api-key? state)
          workspace-name        "Kaitenzushi Corp Acquisition"  ; TODO: derive from actual workspace
          workspace-description "Analyzing Japanese conveyor belt sushi chain for potential acquisition."
          active-topic-id       (topic-state/get-active-topic-id state)
          topics-map            (topic-state/get-topics-map state)]
      [e/app-layout
       ;; ... rest of existing UI code ...
       ])
    ;; Show welcome screen when no workspace loaded
    (welcome-ui/render-welcome)))


5. Register the new action in src/gremllm/renderer/actions.cljs


(nxr/register-action! :workspace.actions/open-folder workspace/open-folder)


6. Modify src/gremllm/main/actions.cljs

Add handler for the workspace open request from renderer:


;; Add this IPC handler registration where other handlers are registered
(nxr/register-handler! :ipc/workspace:open-folder-request
  (fn [_state _event]
    [[:workspace.effects/pick-and-load-folder]]))


7. Clean up src/gremllm/main/io.cljs

Remove the default-workspace constant and its usage:


;; Remove this line:
(def ^:private default-workspace "default")

;; Update workspace-dir-path to require workspace-id:
(defn workspace-dir-path
  "Path to a workspace directory: <userData>/User/workspaces/<workspace-id>"
  [user-data-dir workspace-id]
  (user-dir-path user-data-dir workspaces-subdir workspace-id))


This implementation follows our principles:

 • Strict FCIS: UI state (workspace loaded) stays in renderer, folder picking effect in main
 • Modelarity: Workspace becomes an explicit first-class concept in the UI
 • Minimal Complexity: Reuses existing IPC mechanisms and folder picker
 • Pragmatic Evolution: From "auto-load default" to "user chooses workspace" - a natural progression
