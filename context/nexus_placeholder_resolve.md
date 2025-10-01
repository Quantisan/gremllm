Denis McCarthy
  Aug 5th at 5:21 AM
Hi, I’m trying to use a placeholder to handler the results of  a http response, similar to how the nexus documentation itself has the :http.res/header placeholder - but I’m obviously missing something as I can’t get the placeholder to populate with the actual http response. Here’s how I define the placeholder:

```
(nxr/register-placeholder!
  :http.res/body
  (fn [{:keys [response] :as params}]
    (some->> response .-body (cske/transform-keys csk/->kebab-case-keyword))))
Here’s the relevant form from the js/fetch call in my make-http-request function:
(.then (fn [{:keys [response success? status]}]
                 (let [res-clj (js->clj response :keywordize-keys true)
                       kebab-res (cske/transform-keys csk/->kebab-case-keyword res-clj)]
                   (receive ctx (js/Date.) payload (assoc kebab-res :success? success?))
                   (when (and on-success success?)
                     (dispatch on-success {:response response})))))
…and here is the {:on {:submit..  hiccup:
[[:event/prevent-default]
     [:actions/command {:command/kind :command/payout-offer
                        :command/data
                        {:claim-token claim-token
                         :payout-method :revolut
                         :recipient-name (str first-name " " last-name)}}
      {:on-success [[:db/transact [:db/id :payout/payout-info [:http.res/body]]]]}]]
```

The :on-success db/transact is called, but [http.res/body]  resolves to nil. I’ve debugged the placeholder function, and response is nil. Any idea what I’m missing here?
:white_check_mark:
1

Ovidiu Stoica
  Aug 5th at 2:01 PM
This issue is pretty frequent; I posted about it myself earlier in this group. This happens because nexus tries to resolve the placeholder when it tries to execute the actions/command action and not when it tries to execute the db/transact. At the actions/command execution time there isn’t any response in the dispatch data because the fetch didn’t execute yet, so nexus will just directly replace with nil:

```
[:actions/command {:command/kind :command/payout-offer
                        :command/data
                        {:claim-token claim-token
                         :payout-method :revolut
                         :recipient-name (str first-name " " last-name)}}
      {:on-success [[:db/transact [:db/id :payout/payout-info [:http.res/body]]]]}
```
=>
```
[:actions/command {:command/kind :command/payout-offer
                        :command/data
                        {:claim-token claim-token
                         :payout-method :revolut
                         :recipient-name (str first-name " " last-name)}}
      {:on-success [[:db/transact [:db/id :payout/payout-info nil]]]}
```
Currently, there are 2 solutions:
To bypass this, you can make the placeholder logic execute only if there is a response in the dispatch data; otherwise return the original placeholder (I use this technique):
```
(defn get* [m k]
  (if (vector? k)
    (get-in m k)
    (get m k)))

(nxr/register-placeholder! :command/result
  (fn [{:keys [command-response]} ks]
    (if command-response
      (if ks
        (get* (:body command-response) ks)
        (:body command-response))
      ;; Return the original placeholder vector if no command-response
      (if ks
        [:command/result ks]
        [:command/result]))))
```

Or you can do a manual enrichment of the placeholder in the effect handler and not register the query/command placeholders with nexus:
```
;; enrich.cljc
(defn with-result
  "Enriches actions by replacing [key result-key] patterns with values from result.

   Takes a result map, actions data structure, and a key to match against.
   Replaces patterns like [key result-key] with (get result result-key).
   Replaces [key] with the full result.
   Replaces the key alone with the full result.

   Examples:
   (enrich-with-result {:access-token \"tok123\" :user-id 42}
                       [[:action [:command/result :access-token]]]
                       :command/result)
   => [[:action \"tok123\"]]

   (enrich-with-result {:user/name \"John\"}
                       [[:action [:query/result]]]
                       :query/result)
   => [[:action {:user/name \"John\"}]]

   (enrich-with-result {:user/name \"John\"}
                       [[:action :query/result]]
                       :query/result)
   => [[:action {:user/name \"John\"}]]"
  [result actions key]
  (walk/prewalk
    (fn [x]
      (cond
        ;; Handle [key result-key] pattern
        (and (vector? x)
             (= (count x) 2)
             (= (first x) key))
        (get result (second x))

        ;; Handle [key] pattern (single element vector - full result)
        (and (vector? x)
             (= (count x) 1)
             (= (first x) key))
        result

        ;; Handle key alone (full result)
        (= x key)
        result

        :else x))
    actions))

;; effects.cljc
(nxr/register-effect! :data/command
     (fn [{:keys [dispatch]} {:keys [store]} {:keys [command/data command/kind] :as command}
          & [{:keys [on-success on-fail]}]]
       (swap! store command/issue-command (js/Date.) command)
       (-> (q/api "/cqrs/command" (cond-> {:method :post}
                                    (nil? data) (assoc :body {:command/kind kind})
                                    data (assoc :body (assoc data :command/kind kind))))
           (.then
             (fn [{:keys [status body] :as response}]
               (if (< status 400)
                 (do
                   (swap! store command/receive-response (js/Date.) command {:success? true
                                                                             :result body})
                   (when (seq on-success)
                     ;; manual enrichment at :on-success dispatch time
                     (dispatch (enrich/with-result response on-success :command/result))))
                 (do
                   (swap! store command/receive-response (js/Date.) command {:success? false
                                                                             :error body})
                   (when (seq on-fail)
                     (dispatch (enrich/with-result response on-fail :command/result))))))
           (.catch #(swap! store command/receive-response (js/Date.) command {:success? false
                                                                              :error (.-message %)})))))
```


Cormac Cannon
  Aug 8th at 5:06 PM
@Ovi Stoica I was thinking about this family of problems lying in bed this morning, after a late night playing with nexus dispatch. At the expense of some verbosity, could something like the following work as a general solution....
Some new actions in your nexus config (EDITED to correct for extraneous parentheses):

```
    :state/->
    (fn [state path [first & rest :as _partial-action]]
      (let [v (get-in state path)]
        [(into (if first [first v] [v]) rest)]))

    :state/->>
    (fn [state path partial-action]
      [(conj partial-action (get-in state path))])

    :state/interpolate
    (fn [state action placeholder path]
      (let [value (get-in state path)]
        [(walk/postwalk (fn [x] (if (= placeholder x)
                                  value
                                  x))
                        action)]))
```
.... then your nested async dispatch example becomes something like this ....
```
[[:effects/command
  {:command/kind :command/test}
  {:on-success [[:state/interpolate
                 [:effects/command
                  {:command/kind :command/test2
                   :command/data [:command/result]} ;; placeholder for result of first command
                  {:on-success [[:state/->>
                                 [:path :to :command2 :result]
                                 [:state/assoc-in [:test2] [:command/result]]]]}]
                 [:command/result] ;placeholder
                 [:path :to :command1 :result]]]}]]
(originally this, from head of thread, reformatted slightly for comparison)
[[:effects/command
  {:command/kind :command/test}
  {:on-success [[:effects/command
                 {:command/kind :command/test2
                  :command/data [:command/result]} ;; placeholder for result of first command
                 {:on-success [[:state/assoc-in [:test2] [:command/result]]]}]]}]] ;; placeholder for result of second command
(edited)
```
