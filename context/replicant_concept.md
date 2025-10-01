# Replicant Development: Principles and Philosophy

This document outlines the core principles for front-end development using the Replicant model. The primary goal is to create UIs that are **simple, predictable, and highly testable** by strictly adhering to a functional, data-driven approach.

## 1. Core Philosophy

The fundamental axiom is: **The UI is a pure function of application state.**

`UI = f(state)`

This means the entire user interface is a deterministic result of rendering a single, immutable state value. We achieve this through **top-down rendering**, where the whole UI is conceptually re-rendered whenever the state changes. The framework (Replicant) optimizes the actual DOM updates.

## 2. Core Principles

1.  **Single Source of Truth:**
    *   All application state must reside in a single, central Clojure `atom` (the `store`).
    *   The immutable value within the `atom` at any point in time is the `state`.
    *   The UI should be derivable from this `state` value alone.

2.  **Pure View Functions:**
    *   All functions that generate UI (Hiccup) **must be pure**.
    *   They accept domain data as arguments and return Hiccup data.
    *   They must **not** contain side effects, reference global atoms (like the `store`), or have any hidden inputs.

3.  **Unidirectional Data Flow:**
    *   Data flows in a strict, one-way loop:
        1.  `State` is passed to the top-level render function.
        2.  `Render` produces Hiccup, which updates the UI.
        3.  `User Interaction` on the UI triggers an event.
        4.  The event handler dispatches an `action` (data).
        5.  A central `dispatcher` processes the action, mutates the `State` (by `swap!`-ing the store).
        6.  The cycle repeats.

4.  **Data-Driven Interactions:**
    *   Event handlers in Hiccup (e.g., `:on-click`, `:on-submit`) **must not contain functions**.
    *   They must contain **data** that describes the user's intent. This data is called an "action."
    *   **Example:** `[:button {:on-click [[:toggle-task-expanded {:task/id 123}]]} "Toggle"]`

5.  **Centralized Side Effects:**
    *   All side effects (state mutations, API calls, timers, etc.) are orchestrated from a single, global action handler function (e.g., `handle-actions`).
    *   This function is registered with Replicant via `set-dispatch`.
    *   This is the *only* part of the application that is impure and interacts with the outside world. The UI remains completely pure.

## 3. Key Implementation Patterns

### A. Handling Asynchronous Operations

Side effects are modeled as data transformations within the central dispatcher.

*   **API Calls:**
    1.  Dispatch an action like `[:load-tasks]`.
    2.  The handler *immediately* updates the state to a loading status (e.g., `(swap! store assoc-in [:tasks :loading?] true)`).
    3.  The handler then initiates the asynchronous API call.
    4.  The API callback dispatches a *new* action with the result, e.g., `[:load-tasks-success {:tasks [...]}]` or `[:load-tasks-failure {:error ...}]`.

*   **Timed Effects (e.g., Toasts):**
    1.  Model the delay as an action: `[:show-toast {:message "Hello"}]` followed by `[:delay {:ms 3000 :action [:hide-toast]}]`.
    2.  The `handle-actions` function, when it sees `:delay`, will use `setTimeout` to call `handle-actions` again with the nested `:action` after the specified duration.

### B. Handling Forms and Dynamic Input

To keep render functions pure, we cannot access dynamic form values during rendering.

*   **Use Placeholders:** Embed placeholder keywords in your action data.
*   **Example:** `[:input {:on-input [[:update-form-field {:field :title :value :event/value}]]}]`
*   **Resolve at Dispatch:** Before the action is processed by `handle-actions`, a pre-processing step (e.g., a `postwalk`) replaces the placeholder `:event/value` with the actual value from the browser event object (`event.target.value`).

### C. Encapsulating Necessary Impurity

For rare cases requiring direct DOM manipulation (e.g., `autofocus`, third-party library integration), use **Replicant Aliases**.

*   **Define an Alias:** Use `defalias` to create a function that performs the imperative work. This binds the function to a keyword.
*   **Use the Alias:** Use the keyword in your Hiccup as if it were a component tag.
*   **Purpose:** This cleanly separates the small, unavoidable piece of impurity from the rest of your pure UI code, making it an explicit and contained "escape hatch."

## 4. Testing Philosophy

*   **Unit Tests:**
    *   Test your pure view functions. Provide sample domain data and assert on the returned Hiccup.
    *   Use a selector library like **`lookup`** to query the Hiccup structure using CSS selectors. This creates robust tests that focus on behavior and are not brittle to minor design changes (e.g., adding a wrapper `div`).
    *   These tests are pure, fast, and can run on the JVM.

*   **Visual/Integration Tests:**
    *   Use a tool like **Portfolio** (a Storybook for ClojureScript).
    *   Create "scenes" that render components or entire application screens in various static states (e.g., loading, error, empty, with data, dark/light mode).
    *   This is for verifying design, styling, and layout in isolation.