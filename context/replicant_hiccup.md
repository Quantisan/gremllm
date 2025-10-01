Title: Hiccup

URL Source: https://replicant.fun/hiccup/

Markdown Content:
In Replicant, we use Clojure data literals like vectors, keywords, maps and strings to write HTML. When Clojure data is used to write HTML in this way we call it “hiccup”. The idea and name was coined by [James Reeves](https://github.com/weavejester) in his [library of the same name](https://github.com/weavejester/hiccup).

Replicant supports some additional features not available in every other library that supports hiccup. This guide details all the features Replicant supports. If you are missing something, please [file an issue](https://github.com/cjohansen/replicant/issues).

Hiccup
```
[:br]
```
Rendered HTML
```
<br>
```

The vector can be thought of as the brackets and the keyword represents the tag name.

### Allowed tags

So, which tags can you use? Any one that the browser supports, including custom elements created with `window.customElements.define`. Replicant does not maintain a separate list of valid tags. Whatever keyword you put in the first position of the vector will be used as the tag name – just be aware that [namespaced keywords are treated differently](https://replicant.fun/alias/).

[§ Elements](https://replicant.fun/hiccup/#elements)
----------------------------------------------------

Hiccup
```
[:h1 "Hello Replicant"]
```
Rendered HTML
```
<h1>Hello Replicant</h1>
```

Multiple strings are fine. They will be combined to one string with no separators, so make sure to include spaces as required:

Hiccup
```
[:h1 "Hello " given-name ", how are ya?"]
```
Rendered HTML
```
<h1>Hello Christian, how are ya?</h1>
```

You can nest other elements as well:

Hiccup
```
[:ul
 [:li "Data-driven"]
 [:li "Functional"]
 [:li "Unidirectional"]]
```
Rendered HTML
```
<ul>
  <li>Data-driven</li>
  <li>Functional</li>
  <li>Unidirectional</li>
</ul>
```

### [§ Fragments / lists](https://replicant.fun/hiccup/#lists)

The children of an element does not need to appear one after the other as direct descendants of the parent element. Hiccup is typically built with code, and Replicant accounts for this fact.

The following code snippet would put the `li` elements in a list, and that’s OK.

Hiccup
```
[:ul
 (for [fruit ["Banana"
              "Apple"
              "Orange"]]
  [:li fruit])]

;;=>

[:ul
 '([:li "Banana"]
   [:li "Apple"]
   [:li "Orange"])]
```
Rendered HTML
```
<ul>
  <li>Banana</li>
  <li>Apple</li>
  <li>Orange</li>
</ul>
```

In fact, you can nest elements as deeply and irregularly as you want:

Hiccup
```
[:ul
 (map get-preferences people)]

;;=>

[:ul
 '(([:li "Apple"])
   ([:li "Orange"]
    [:li "Banana"])
   ([:li ("Chocolate" "Chips")]))]
```
Rendered HTML
```
<ul>
  <li>Apple</li>
  <li>Orange</li>
  <li>Banana</li>
  <li>Chocolate Chips</li>
</ul>
```

Replicant doesn’t support document fragments directly, but lists of elements have the same effect in most cases.

`replicant.dom/render` and `replicant.string/render` both accept lists of hiccup nodes, so you don’t have to wrap everything in a div if you don’t want to.

```
(require '[replicant.dom :as r])

(r/render
  js/document.body
  (list [:h1 "Hello world!"]
        [:p "What a lovely world it is"]))
```

### `nil` children

`nil`s are perfectly fine as children. This means it’s safe to produce hiccup with code that uses `when`, or calls functions that may or may not return hiccup to be used as children. To learn how explicit `nil`s can help Replicant make better choices, check out [explicit nils](https://replicant.fun/nil/).

[§ Attributes](https://replicant.fun/hiccup/#attributes)
--------------------------------------------------------

To give an element attributes, place a map next to the tag name. Attributes use the same name as in the DOM API, and can be keywords, strings or symbols. Typically keywords are used:

Hiccup
```
[:img {:src "/images/homer.jpg"
       :width 160
       :height 90}]
```
Rendered HTML
```
<img src="/images/homer.jpg"
     width="160"
     height="90">
```

You can of course have both attributes and children:

Hiccup
```
[:div {:data-theme "cupcake"}
 [:a {:href "https://vimeo.com/861600197"}
  [:img
   {:src "/images/data-driven.png"
    :alt "Watch talk"}]]]
```
Rendered HTML
```
<div data-theme="cupcake">
  <a href="https://vimeo.com/861600197">
    <img src="/images/data-driven.png"
         alt="Watch talk">
  </a>
</div>
```

Attributes can have explicit nil values. If they do, Replicant behaves as though the attribute wasn’t there. This means it’s safe to wrap an attribute’s value in a `when`, and you don’t have to check every possible attribute for `nil`:

Hiccup
```
(defn media [{:keys [url thumbnail
                     title playing?]}]
  [:div.media {:data-theme "cupcake"}
   [:a {:href url
        :class (when playing?
                 "spinner")}
    [:img
     {:src thumbnail
      :alt title}]]])

;;=>
[:div.media {:data-theme "cupcake"}
 [:a {:href "https://vimeo.com/861600197"
      :class nil}
  [:img {:src "/images/data-driven.png"
         :alt nil}]]]
```
Rendered HTML
```
<div data-theme="cupcake">
  <a href="https://vimeo.com/861600197">
    <img src="/images/data-driven.png">
  </a>
</div>
```

### [§ Id](https://replicant.fun/hiccup/#id)

You can use CSS selector syntax to add an id directly to the hiccup tag name:

Hiccup
```
[:h1#heading "Hello!"]
```
Rendered HTML
```
<h1 id="heading">Hello!</h1>
```

Ids on the tag name is practical when you can type in both at the same time. But you can also provide an `:id` attribute in the attribute map when appropriate, e.g. when the id is a computed value:

Hiccup
```
(let [id (str (:kind content))]
  [:h1 {:id id} (:text content)])
```
Rendered HTML
```
<h1 id="heading">Hello</h1>
```

### [§ Classes](https://replicant.fun/hiccup/#class)

Classes are arguably the most commonly used attributes. Because they are so common, classes can be added directly to the tag name — just like you would write a CSS selector:

Hiccup
```
[:img.rounded-lg.block
 {:src "/data-driven.png"}]
```
Rendered HTML
```
<img class="rounded-lg block"
     src="/data-driven.png">
```

Multiple classes are separated by a dot. If you also use the CSS selector syntax for ids, the id needs to preceed the classes:

Hiccup
```
[:img#image.rounded-lg.block
 {:src "/data-driven.png"}]
```
Rendered HTML
```
<img id="image"
     class="rounded-lg block"
     src="/data-driven.png">
```

Like with ids, tacking classes on to the tag name is very practical when you can type them both out ahead of time. However, conditional or computed classes are better added to the `:class` attribute.

The `:class` attribute supports strings, keywords, and a collection of strings and/or keywords.

Hiccup
```
[:img
 {:src "/data-driven.png"
  :class [:rounded-lg "block"]}]
```
Rendered HTML
```
<img class="rounded-lg block"
     src="/data-driven.png">
```

You can combine selector style classes and the `:class` attribute:

Hiccup
```
[:img.rounded-lg
 {:src "/data-driven.png"
  :class "block"}]
```
Rendered HTML
```
<img class="rounded-lg block"
     src="/data-driven.png">
```

Passing a string with space-separated classes, e.g. `[:img {:class "rounded-lg block"}]` will work but is discouraged. In fact, it will produce a console error during development. The reason is that Replicant has to parse the string to understand it. Use a collection instead.

### Styles

The `:style` attribute can take a literal HTML style attribute value, like `"background: #000; color: #fff"`, but who wants to write CSS in a string? Replicant also supports passing a map to the `:style` attribute. The map keys should be keywords, and the values should be strings or numbers:

Hiccup
```
[:h1
 {:style
  {:font-family "helvetica"
   :font-weight 900
   :max-width 800}}
 "Hello!"]
```
Rendered HTML
```
<h1 style="font-family: helvetica;
           font-weight: 900;
           max-width: 800px;">
  Hello!
</h1>
```

Replicant treats most numbers as pixel values, but knows which CSS properties take numeric values that aren’t pixels. Style properties are spelled just like you would spell them in a CSS file.

### [§ innerHTML](https://replicant.fun/hiccup/#inner-html)

While not ideal, sometimes you have a string that contains some HTML (like the output of a CMS WYSIWYG field) and you just want to insert it into the DOM tree without any further hassle. Replicant’s got you covered: use the `:innerHTML` “attribute”.

When rendering to a string on the server, `:innerHTML` is how you can output an unescaped string into the generated DOM.

Hiccup
```
[:div
 {:innerHTML
  "<h1>Hello there!</h1>"}]
```
Rendered HTML
```
<div>
  <h1>Hello there!</h1>
</div>
```

When an element has the `:innerHTML` attribute, its child nodes are completely ignored and the node’s content will be dictated by the `:innerHTML` attribute:

Hiccup
```
[:div
 {:innerHTML "<h1>Oops!</h1>"}
 [:p "This will be ignored"]
```
Rendered HTML
```
<div>
  <h1>Oops!</h1>
</div>
```

`:innerHTML` is an escape hatch. Most people won’t need to ever use it.

### [§ Event handlers](https://replicant.fun/hiccup/#event-handlers)

All event handlers go in a map under the `:on` attribute key. Event handler names are the same ones as in the browser. Replicant does not keep a list of valid names to use – whatever you pass it, it will pass to `addEventListener`:

Hiccup
```
[:button
 {:on {:click
       (fn [e]
         (js/alert "Hello!"))}}
 "Click it"]
```
Rendered HTML
```
<button
  on-click="alert('Hello!')">
  Click it
</button>
```

Replicant does no special handling of the event handler function: it will behave exactly as if you added it with `.addEventListener`. That means that `e` is a plain old JavaScript `Event` object.

Event handlers can also be expressed as data:

```
[:button {:on {:click [:alert "Hello!"]}}
 "Click it"]
```

Data event handlers requires some additional setup, see the [detailed guide on event handlers](https://replicant.fun/event-handlers/).

To control options such as `capture` and `passive` when setting the event handler see [event handler options](https://replicant.fun/event-handlers/#options).

### Life-cycle hooks

Sometimes you need access to the DOM nodes. Maybe you want to know how something actually turned out, e.g. measure its rendered size, or you want to integrate with a third party JavaScript library such as a map toolkit etc.

You can use the special “attribute” `:replicant/on-render` to register a life-cycle hook. It will be called whenever the underlying DOM node is changed somehow:

```
[:div {:replicant/on-render
       (fn [data] (prn data))}
  "Hello!"]
```

The function is called with a single argument, which is a map of these keys:

*   `:replicant/trigger` Always has the value `:replicant.trigger/life-cycle`
*   `:replicant/life-cycle` One of `:replicant.life-cycle/mount` (initial render), `:replicant.life-cycle/update` (successive updates) or `:replicant.life-cycle/unmount` (node is being removed from the DOM).
*   `:replicant/node` The DOM node

`:replicant/on-render` triggers on all updates and gives you enough information to know what happened. If you just want to do something on mount and/or unmount, you can use `:replicant/on-mount` and `:replicant/on-unmount`, which work exactly like `:replicant/on-render`, except they only trigger on their respective life-cycle events.

Like event handlers, life-cycle hooks can be expressed as data see the [detailed guide on life-cycle hooks](https://replicant.fun/life-cycle-hooks/).

### Mounting and unmounting

Elements can have attribute overrides when they are added to or removed from the DOM. These can be used to create transitions or animate SVGs etc. `:replicant/mounting` is an attribute map that will be merged into the element’s attributes when mounting, and `:replicant/unmounting` does the same when it unmounts.

```
[:div
 {:style {:opacity 1
          :height 100
          :width 200
          :background "#6180D2"
          :transition "opacity 0.25s,
                       height 0.25s,
                       width 0.25s,
                       backgroun 0.25s"}

  :replicant/mounting
  {:style {:opacity 0}}

  :replicant/unmounting
  {:style {:height 0
           :width 0
           :background "#76AF47"}}}]
```

This will create a blue square. As it mounts, the opacity style property will be set to 0, then changed to 1, which will trigger a transition. As it unmounts, the height and width properties are both set to 0, and the background is changed to green – all causing transitions.

In less technical terms: this creates a blue square that fades in on mount, and changes to color to green as it shrinks to nothing on unmount.

### [§ Keys](https://replicant.fun/hiccup/#keys)

`:replicant/key` can be used to identify an element among its siblings. Using keys helps avoid elements being replaced and recreated.

```
[:form
 [:label {:for "name"} "Name:"]
 [:input {:type "text"
          :name "name"
          :id "name"
          :replicant/key [:input "name"]}]]
```

Keys can be arbitrary Clojure data.

Keys are usually not required, but you may want to use them on elements with transitions, animations or state (e.g. `input`, `select`, etc). See the [detailed guide on keys](https://replicant.fun/keys/).

### Form elements

If you set the `:value` attribute of an `input` field, you are in control of its value. To have the field display the user’s input, you must register an event handler to listen to input and re-render with the provided input. React used to call this a “controlled component”. If only want to set the initial value of a form field, but not control the value after rendering, you can set the default value instead. Use any of the following attributes to set the initial value of an uncontrolled input:

*   `:default-value` for `:value`
*   `:default-checked` for `:checked`
*   `:default-selected` for `:selected`
