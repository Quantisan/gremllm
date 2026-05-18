(ns gremllm.schema.codec.acp.permission
  "Pure permission policy for ACP. Operates on coerced CLJS maps.
   Use gremllm.schema.codec.acp/acp-permission-request-from-js and
   acp-session-update-from-js to produce inputs for these fns."
  (:require ["path" :as path-module]))

(defn requested-tool-name
  "Return the tool name from a coerced AcpPermissionToolCall, or nil."
  [{:keys [tool-name]}]
  (when (seq tool-name) tool-name))

(defn requested-path
  "Return the file path from a coerced AcpPermissionToolCall's :raw-input, or nil."
  [{:keys [raw-input]}]
  (or (:path raw-input) (:file-path raw-input)))

(defn within-root?
  "True when candidate-path is equal to or nested under root-path."
  [candidate-path root-path]
  (let [relative (.relative path-module root-path candidate-path)]
    (or (= relative "")
        (and (not (.startsWith relative ".."))
             (not (.isAbsolute path-module relative))))))

;; Registry pattern for ACP permission requests:
;;   ACP types RequestPermissionRequest.toolCall as ToolCallUpdate (a delta);
;;   only :tool-call-id is required and there is no tool_name field anywhere
;;   in ACP. We seed a {tool-call-id → tool-name} map from
;;   _meta.claudeCode.toolName on prior tool_call session updates, then
;;   resolve at permission time. Zed implements the same pattern:
;;     zed-industries/zed crates/agent_servers/src/acp.rs (handle_request_permission)
;;     zed-industries/zed crates/acp_thread/src/acp_thread.rs (index_for_tool_call + update_fields)
;;     zed-industries/agent-client-protocol src/v1/client.rs (RequestPermissionRequest)
;;   Zed uses its own _meta key for the same workaround.

(defn remember-tool-name
  "Return an updated tool-names map with tool name from session-update recorded.
   Only records when both tool-call-id and tool-name are present in the update."
  [tool-names session-update]
  (let [{:keys [tool-call-id]} (:update session-update)
        tool-name (get-in session-update [:update :meta :claude-code :tool-name])]
    (if (and tool-call-id (seq tool-name))
      (assoc tool-names tool-call-id tool-name)
      tool-names)))

(defn enrich-permission-params
  "Return permission-request with :tool-name resolved from tool-names map when absent.
   Looks up tool-call-id in tool-names as a fallback when :tool-name is missing."
  [tool-names permission-request]
  (let [{:keys [tool-call]} permission-request
        {:keys [tool-call-id tool-name]} tool-call
        tracked-name (or (when (seq tool-name) tool-name)
                         (get tool-names tool-call-id))]
    (if (seq tracked-name)
      (assoc-in permission-request [:tool-call :tool-name] tracked-name)
      permission-request)))

(defn- select-option
  "Select the first option matching a preferred kind, or apply fallback-fn to all options."
  [options preferred-kinds fallback-fn]
  (or (some (fn [kind] (first (filter #(= (:kind %) kind) options))) preferred-kinds)
      (fallback-fn options)))

(defn resolve-permission
  "Determine permission outcome from a coerced AcpPermissionRequest and session cwd.

   Returns a tagged result:
     {:resolution :immediate
      :outcome    {:outcome \"selected\" :option-id \"...\"}}   ; or :outcome \"cancelled\"
     {:resolution :deferred
      :tool-call-id \"tc\"
      :diffs        [{:type \"diff\" :path ... :old-text ... :new-text ...}]}

   :deferred is returned for in-workspace edits — the caller is responsible
   for stashing a resolver keyed by :tool-call-id and surfacing :diffs to the
   user for accept/reject. All other paths (read, fetch, out-of-workspace
   edit, unhandled kinds) return :immediate with a synchronous outcome."
  [permission-request session-cwd]
  (let [{:keys [options tool-call]} permission-request
        {:keys [kind tool-call-id]} tool-call]
    ;; Policy (not transport validation): an empty options list means the agent
    ;; offered nothing actionable, so cancel rather than fabricate a selection.
    (if (empty? options)
      {:resolution :immediate
       :outcome    {:outcome "cancelled"}}
      (case kind
        "read"
        (let [opt (select-option options ["allow_always" "allow_once"] first)]
          {:resolution :immediate
           :outcome    {:outcome "selected" :option-id (:option-id opt)}})

        "edit"
        ;; In-workspace edit defers to the user via the pending-diffs UI.
        ;; Out-of-workspace edit auto-rejects immediately (preserves the
        ;; workspace-root guard from the prior policy).
        (let [raw-path       (requested-path tool-call)
              normalized-cwd (when session-cwd (.resolve path-module session-cwd))
              normalized-req (when raw-path (.resolve path-module raw-path))]
          (if (and normalized-cwd normalized-req (within-root? normalized-req normalized-cwd))
            {:resolution    :deferred
             :tool-call-id  tool-call-id
             :diffs         (let [content (:content tool-call)
                                  diffs   (when content
                                            (filterv #(= "diff" (:type %)) content))]
                              (when (seq diffs) diffs))}
            (let [opt (select-option options ["reject_once" "reject_always"] last)]
              {:resolution :immediate
               :outcome    {:outcome "selected" :option-id (:option-id opt)}})))

        "fetch"
        ;; WebSearch is read-only and idempotent; mirror the "read" policy
        ;; (prefer allow_always, fall back to allow_once). Any other fetch
        ;; tool (notably WebFetch) falls through to reject — round 2 will
        ;; route those to a renderer-side permission dialog.
        (if (= "WebSearch" (requested-tool-name tool-call))
          (let [opt (select-option options ["allow_always" "allow_once"] first)]
            {:resolution :immediate
             :outcome    {:outcome "selected" :option-id (:option-id opt)}})
          (let [opt (select-option options ["reject_once" "reject_always"] last)]
            {:resolution :immediate
             :outcome    {:outcome "selected" :option-id (:option-id opt)}}))

        ;; Default: no policy defined for this kind yet — reject until classified.
        (let [opt (select-option options ["reject_once" "reject_always"] last)]
          {:resolution :immediate
           :outcome    {:outcome "selected" :option-id (:option-id opt)}})))))
