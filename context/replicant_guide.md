# Replicant Guide for LLMs

This guide provides a comprehensive overview of Replicant, a ClojureScript hiccup to HTML/DOM rendering library. It covers key concepts, best practices, and patterns for effectively working with Replicant.

## Overview

Replicant is a Clojure(Script) library for rendering hiccup to HTML/DOM. The core philosophy is **top-down rendering**, where UI is a pure function of application state. Unlike component-based frameworks, Replicant strictly separates rendering from state management and side effects.

Key characteristics:
- Pure functional rendering (no local component state)
- Data-driven approach to UI
- Predictable rendering flow
- Clear separation of concerns

## Hiccup Syntax

### Basic Syntax

Hiccup represents HTML using Clojure vectors with a keyword as the first element:

```clojure
;; Basic element
[:div "Hello world"]

;; With attributes
[:div {:class "container"} "Hello world"]

;; Nested elements
[:div
 [:h1 "Title"]
 [:p "Content"]]
```

### Attributes

Attributes are specified as a map in the second position:

```clojure
;; Standard attributes
[:img {:src "/images/photo.jpg" :alt "Photo"}]

;; IDs and classes can be part of the tag
[:div#main.container "Content"] ;; equivalent to [:div {:id "main" :class "container"} "Content"]

;; Classes can be specified multiple ways
[:div.container.mt-4 "Content"]
[:div {:class [:container "mt-4"]} "Content"]

;; Styles can be a map
[:div {:style {:color "red" :font-size "16px"}} "Styled text"]
```

### Event Handlers

Event handlers are specified in a map under the `:on` key:

```clojure
;; Function event handler
[:button
 {:on {:click (fn [e] (js/alert "Clicked!"))}}
 "Click me"]

;; Data-driven event handler (requires setup)
[:button
 {:on {:click [[:action/alert "Button clicked"]]}}
 "Click me"]
```

### Special Attributes

Replicant supports several special attributes:

- `:replicant/key` - Helps manage DOM node identity and reuse
- `:replicant/on-mount` - Hook called when element is added to DOM
- `:replicant/on-unmount` - Hook called when element is removed from DOM
- `:replicant/on-render` - Hook called on any DOM update
- `:replicant/mounting` - Attribute overrides when mounting
- `:replicant/unmounting` - Attribute overrides when unmounting
- `:innerHTML` - Insert raw HTML (use with caution)

## Core Concepts

### Top-Down Rendering

In Replicant, the entire UI is re-rendered whenever application state changes:

```clojure
(defn render-app [state]
  [:div
   [:h1 (:title state)]
   [:div.content (:content state)]])

;; When state changes, call:
(r/render js/document.body (render-app new-state))
```

This pattern ensures UI is always a pure function of state, with no hidden internal state or side effects.

### Event Handling

Replicant supports both function-based and data-driven event handlers:

#### Function handlers:
```clojure
[:button
 {:on {:click (fn [e] (do-something e))}}
 "Click me"]
```

#### Data-driven handlers (requires setup):
```clojure
;; Setup dispatch once
(r/set-dispatch!
 (fn [event-data handler-data]
   (execute-actions handler-data)))

;; Use data as handlers
[:button
 {:on {:click [[:action/do-something "arg1" "arg2"]]}}
 "Click me"]
```

### Life-Cycle Hooks

Access the DOM elements with lifecycle hooks:

```clojure
[:div
 {:replicant/on-mount
  (fn [{:keys [replicant/node]}]
    (js/console.log "Element mounted:" node))}
 "This element has hooks"]
```

Lifecycle hooks can also be data-driven:

```clojure
[:div
 {:replicant/on-mount [[:action/log-mount]]}
 "This element has data-driven hooks"]
```

### Keys

Use keys to help Replicant identify elements across renders:

```clojure
(for [item items]
  [:li {:replicant/key (:id item)}
   (:text item)])
```

Keys are most useful for:
- Lists of similar elements
- Elements with transitions
- Stateful elements (forms, etc.)

## State Management Patterns

Replicant doesn't include state management, giving you the freedom to choose your approach:

### Atom-Based State

```clojure
(defonce store (atom {}))

(defn render [state]
  [:div
   [:h1 "Counter: " (:count state)]
   [:button
    {:on {:click [[:store/update-count inc]]}}
    "Increment"]])

(defn execute-actions [actions]
  (doseq [[action & args] actions]
    (case action
      :store/update-count (swap! store update :count (first args))
      nil)))

(add-watch store ::render
  (fn [_ _ _ state]
    (r/render js/document.body (render state))))
```

### Datascript-Based State

```clojure
(def schema {:task/name {:db/cardinality :db.cardinality/one}
             :task/completed? {:db/cardinality :db.cardinality/one}})

(defonce conn (d/create-conn schema))

(defn get-tasks [db]
  (d/q '[:find ?e ?name ?completed
         :where
         [?e :task/name ?name]
         [?e :task/completed? ?completed]]
       db))

(defn render [db]
  [:div
   [:h1 "Tasks"]
   [:ul
    (for [[id name completed] (get-tasks db)]
      [:li {:replicant/key id}
       [:input
        {:type "checkbox"
         :checked completed
         :on {:change [[:toggle-task id]]}}]
       [:span name]])]])

(defn execute-actions [actions]
  (doseq [[action & args] actions]
    (case action
      :toggle-task
      (let [id (first args)]
        (d/transact! conn [{:db/id id
                            :task/completed? (not (:task/completed? (d/entity @conn id)))}]))
      nil)))

(d/listen! conn ::render
  (fn [_] (r/render js/document.body (render @conn))))
```

## Network Requests

Since Replicant components are pure functions, network requests happen outside the render function:

```clojure
;; Action handler
(defn execute-actions [store actions]
  (doseq [[action & args] actions]
    (case action
      :fetch/users
      (do
        (swap! store assoc :loading? true)
        (-> (js/fetch "/api/users")
            (.then #(.json %))
            (.then #(swap! store assoc :users % :loading? false))
            (.catch #(swap! store assoc :error % :loading? false))))
      nil)))

;; UI component
(defn users-list [state]
  [:div
   [:h1 "Users"]
   [:button
    {:on {:click [[:fetch/users]]}
     :disabled (:loading? state)}
    "Load Users"]
   (cond
     (:loading? state) [:div "Loading..."]
     (:error state) [:div.error "Error loading users"]
     :else
     [:ul
      (for [user (:users state)]
        [:li {:replicant/key (:id user)}
         (:name user)])])])
```

## Forms

### Basic Approach

Control form inputs through the application state:

```clojure
(defn form [state]
  (let [name (get-in state [:form :name] "")]
    [:form
     {:on {:submit [[:event/prevent-default]
                    [:submit-form]]}}
     [:input
      {:type "text"
       :value name
       :on {:input [[:update-form-field :name :event/target.value]]}}]
     [:button
      {:type "submit"
       :disabled (empty? name)}
      "Submit"]]))

(defn execute-actions [store actions]
  (doseq [[action & args] actions]
    (case action
      :update-form-field
      (let [[field value] args]
        (swap! store assoc-in [:form field] value))

      :submit-form
      (let [form-data (get @store :form)]
        (swap! store assoc :submitting? true)
        (-> (js/fetch "/api/submit"
                      #js {:method "POST"
                           :body (js/JSON.stringify (clj->js form-data))})
            (.then #(swap! store dissoc :form :submitting?))
            (.catch #(swap! store assoc :error %))))
      nil)))
```

## Best Practices

1. **Keep render functions pure**
   - No side effects
   - No network requests
   - No DOM manipulation directly

2. **Centralize state management**
   - Use a single source of truth
   - Handle all state updates in one place
   - Make state changes explicit

3. **Use data-driven event handlers**
   - Aids in debugging
   - Makes code more declarative
   - Enables serialization

4. **Optimize with keys**
   - Use keys for lists of similar elements
   - Use keys for elements with transitions
   - Use unique, stable values as keys

5. **Separate concerns**
   - UI rendering
   - State management
   - Network requests
   - Side effects

6. **Structure your application functionally**
   - Pure UI functions
   - Central state atom/database
   - Event/action handlers

7. **Use composition over inheritance**
   - Break down complex UIs into smaller functions
   - Compose these functions together

## Common Patterns

### Conditional Rendering

```clojure
(defn render-content [state]
  [:div
   (when (:show-header? state)
     [:header "Header"])
   (if (:loading? state)
     [:div "Loading..."]
     [:main (:content state)])
   (case (:status state)
     :success [:div.success "Operation successful"]
     :error [:div.error "An error occurred"]
     nil)])
```

### List Rendering

```clojure
(defn render-list [items]
  [:ul
   (for [item items]
     [:li {:replicant/key (:id item)}
      (:text item)])])
```

### Transitions and Animations

```clojure
(defn fade-in-element [content]
  [:div.transition-opacity.duration-300
   {:replicant/mounting {:class "opacity-0"}
    :class "opacity-100"}
   content])
```

### Controlled Inputs

```clojure
(defn controlled-input [value on-change]
  [:input
   {:type "text"
    :value value
    :on {:input [[:update-input :event/target.value]]}}])
```

## Gotchas and Tips

1. **Remember hiccup is data**
   - You can manipulate it with standard Clojure functions
   - You can build it conditionally
   - You can store parts of it in variables

2. **Functions are not data**
   - Event handlers create new functions on each render
   - Use data-driven handlers when possible

3. **Careful with controlled vs uncontrolled inputs**
   - Setting `:value` makes an input controlled
   - Use `:default-value` for uncontrolled inputs

4. **Don't forget to prevent default on form submissions**
   - Use `[:event/prevent-default]` action

5. **Keys are local to siblings**
   - Keys only need to be unique among immediate siblings
   - Different levels can reuse the same key values

6. **Transitions need planning**
   - Use `:replicant/mounting` and `:replicant/unmounting`
   - Elements need keys to properly animate unmounting

## Example Application Structure

```
src/
  my_app/
    core.cljs         ; Main entry point, setup
    state.cljs        ; State management (atom/datascript)
    actions.cljs      ; Action handlers
    ui/
      components.cljs ; Reusable UI components
      pages.cljs      ; Page-level UI components
      forms.cljs      ; Form-related components
    util/
      network.cljs    ; Network utilities
      formatters.cljs ; Data formatting utilities
```

## Core Functions

```clojure
;; DOM rendering
(replicant.dom/render container hiccup)

;; String rendering
(replicant.string/render hiccup)

;; Set up global handler for data-driven events
(replicant.dom/set-dispatch! dispatch-fn)

;; Register a hiccup alias
(replicant.alias/register! :my/component my-component-fn)
```

By understanding these concepts and patterns, you'll be able to effectively use Replicant to build clean, maintainable front-end applications with a clear separation of concerns.
