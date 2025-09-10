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

# Implementation Progress

## ✅ DONE

1. **Created src/gremllm/renderer/state/workspace.cljs**
   - Implemented with `loaded-path` and `workspace-path-path`
   - Added `loaded?` and `get-workspace-path` accessor functions

2. **Refactored workspace loading to set loaded state**
   - Previously `populate-topics`, now split into domain-obvious actions:
     - `:workspace.actions/opened` - Entry point when workspace data arrives
     - `:workspace.actions/restore-with-topics` - Restores workspace with existing topics
     - `:workspace.actions/initialize-empty` - Initializes empty workspace
     - `:workspace.actions/mark-loaded` - Explicitly marks workspace as loaded
   - All paths through workspace loading now call `mark-loaded`

## TODO

3. **Create src/gremllm/renderer/ui/welcome.cljs**
   ```clojure
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

4. Modify src/gremllm/renderer/ui.cljs
  • Add conditional rendering based on workspace-state/loaded?
  • Show welcome screen when no workspace loaded
  • Show existing chat UI when workspace is loaded

5. Modify src/gremllm/renderer/actions/workspace.cljs
  • Update bootstrap to not auto-load:

    (defn bootstrap [_state]
      ;; No longer auto-load. User must explicitly open a workspace.
      [])

  • Add open-folder action:

    (defn open-folder [_state]
      ;; Send command to main process to show folder picker
      [[:workspace.effects/request-open-folder]])

  • Register the request effect


6. Register new action in src/gremllm/renderer/actions.cljs

  (nxr/register-action! :workspace.actions/open-folder workspace/open-folder)

7. Add IPC handler in main process
  • Handle workspace open request from renderer
  • Trigger folder picker dialog

8. Clean up src/gremllm/main/io.cljs
  • Remove default-workspace constant
  • Update workspace-dir-path to require explicit workspace-id

### This implementation follows our principles:

 • Strict FCIS: UI state (workspace loaded) stays in renderer, folder picking effect in main
 • Modelarity: Workspace becomes an explicit first-class concept in the UI
 • Minimal Complexity: Reuses existing IPC mechanisms and folder picker
 • Pragmatic Evolution: From "auto-load default" to "user chooses workspace" - a natural progression
