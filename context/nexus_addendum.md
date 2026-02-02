# Nexus: Addendum & Gotchas

Lessons learned and edge cases discovered while working with Nexus.

---

## State Capture Timing

### The Problem: Actions See Stale State

Nexus captures state **once** at the beginning of `dispatch`, then uses that same immutable snapshot for **all** action expansions in the chain. Effects only mutate state **after** all actions are fully expanded.

This can cause subtle bugs when an action needs to read state that was saved by an earlier effect in the same dispatch chain.

### Example: The Init-Session Bug

Consider this action chain:

```clojure
(defn restore-with-topics [_state topics]
  [[:effects/save topics-path topics]           ;; Effect 1
   [:topic.actions/set-active topic-id]         ;; Action 1
   [:workspace.actions/mark-loaded]])           ;; Action 2

(defn set-active [_state topic-id]
  [[:effects/save active-topic-id-path topic-id]  ;; Effect 2
   [:acp.actions/init-session topic-id]])          ;; Action 2 (problematic!)

(defn init-session [state topic-id]
  (let [existing-id (get-topic-field state topic-id :acp-session-id)]
    (if existing-id
      [[:acp.actions/resume-session topic-id existing-id]]
      [[:acp.actions/new-session topic-id]])))
```

**What happens:**

```
1. dispatch captures state S0 (no topics yet)
2. Nexus expands restore-with-topics using S0
   → returns Effect 1, Action 1, Action 2
3. Nexus expands set-active using S0
   → returns Effect 2, Action 2 (init-session)
4. Nexus expands init-session STILL using S0
   → reads (get-topic-field S0 topic-id :acp-session-id)
   → returns nil because topics haven't been saved yet!
   → always creates new session instead of resuming
5. THEN Nexus runs all effects in order
   → Effect 1 saves topics to atom
   → Effect 2 saves active-topic-id
   → But it's too late - init-session already decided to create new session
```

The `init-session` action reads from state S0, which doesn't have the topics that will be saved by Effect 1. By the time effects actually run and mutate the atom, the action has already made its decision based on stale data.

### The Solution: Convert to an Effect

Effects run **after** all action expansion completes, and they receive the **live store atom**. By converting `init-session` from an action to an effect, it can deref the store to see state that includes all previously-saved data.

**Fixed implementation:**

```clojure
;; In set-active action - call as effect instead
(defn set-active [_state topic-id]
  [[:effects/save active-topic-id-path topic-id]
   [:acp.effects/init-session topic-id]])  ;; Effect, not action!

;; In actions.cljs shell - inline as effect registration
(nxr/register-effect! :acp.effects/init-session
  (fn [{:keys [dispatch]} store topic-id]
    (let [state @store  ;; Deref gets current state!
          existing-id (get-topic-field state topic-id :acp-session-id)]
      (if existing-id
        (dispatch [[:acp.actions/resume-session topic-id existing-id]])
        (dispatch [[:acp.actions/new-session topic-id]])))))
```

**New flow:**

```
1. dispatch captures state S0
2. Nexus expands all actions using S0
   → returns Effect 1, Effect 2, Effect 3 (init-session)
3. Nexus runs effects in order:
   → Effect 1 runs: topics saved to atom
   → Effect 2 runs: active-topic-id saved
   → Effect 3 runs: @store now has topics! ✓
```

### When to Use Effects vs Actions

**Use an action when:**
- Logic is pure business logic
- Doesn't need to see state mutations from earlier effects in the same dispatch
- Should be testable without mocking

**Convert to an effect when:**
- You need to see state that was saved by earlier effects in the same dispatch chain
- You need to perform side effects (IPC calls, file I/O, etc.)
- The timing of state mutations matters for your logic

### Key Insight: FCIS and the Shell

This pattern follows the Functional Core, Imperative Shell principle:

- **Actions** are the functional core - pure functions receiving immutable state
- **Effects** are the imperative shell - impure functions with access to live system state

When you need to bridge between saved effects and decision logic, you're at the boundary between core and shell. That's when you inline the effect in the shell's action registration file (`actions.cljs` in the renderer process) rather than keeping it as a pure action in a domain namespace.

The effect registration lives in the shell because:
1. It performs side effects (derefs the store atom)
2. It needs to see live, mutated state
3. It's coordinating between effects and actions

This is architectural—effects live in the shell by design.
