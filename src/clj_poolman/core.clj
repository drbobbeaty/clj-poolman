(ns clj-poolman.core)

(defn next-id
  "Find the next resource id"
  [resources]
  (let [ids (set (map :id resources))]
    (first (filter #(not (ids %)) (iterate inc 0)))))

(defn new-resource
  "Make a new resource of a pool"
  [f-init resources]
  (let [id (next-id resources)]
    {:id id :resource (f-init)}))

(defn assoc-new-resource
  [{:keys [resources init] :as pool}]
  (assoc pool :resources (conj resources (new-resource init resources))))

(defstruct resource-pool :init :close :low :high :resources)
	 
(defn mk-pool*
  "Make a new resource pool where high for high watermark, low for low watermark,
   f-init is a function without argument to open a new resource,
   f-close is a function which take resource as a argument and do something to release the resource,
   the return value of f-close will be ignored"
  [high low f-init f-close]
  {:pre [(>= high low) f-init]}
  (let [pool (struct resource-pool f-init f-close low high #{})]
    (reduce (fn [p _] (assoc-new-resource p)) pool (range low))))

(defn get-resource
  "Low level resource getting process. Use with-resource macro instead."
  [{:keys [init high resources] :as pool}]
  (let [free-resources (filter #(not (:busy %)) resources)
	resource (if (seq free-resources)
		   (first free-resources)
		   (when (> high (count resources))
		     (new-resource init resources)))
	resource-after (assoc resource :busy true)
	resources (-> resources (disj resource) (conj resource-after))	
	pool (if resource
	       (assoc pool :resources resources)
	       pool)]
    [pool resource]))
    
(defn release-resource
  "Low level resource releasing. Use with-resource macro instead."
  [{:keys [low close resources] :as pool} {res-id :id :as resource}]
  (let [busy-resource (first (filter #(= (:id %) res-id) resources))
	resources (-> resources (disj busy-resource))
	resources (if (>= (count resources) low)
		   (do
		     (when close
		       (close (:resource resource)))
		     resources)
		   (conj resources resource))]
    (assoc pool :resources resources)))

(defn mk-pool
  "Make a mutable resource pool."
  [high low f-init f-close]
  (atom (mk-pool* high low f-init f-close)))

(defn shutdown-pool*
  "Intenal function, to shutdown a resource pool."
  [{:keys [resources close]}]
  (when close
    (dorun (map #(close (:resource %)) resources))))

(defn shutdown-pool
  "Shutdown a mutable resource pool"
  [ref-pool]
  (shutdown-pool* @ref-pool))

(defmacro with-resource
  "Get a resource from a pool, bind it to res-name, so you can use it in body,
   after body finish, the resource will be returned to the pool."
  [[res-name ref-pool] & body]
  `(let [[new-pool# resource#] (get-resource (deref ~ref-pool))]
     (reset! ~ref-pool new-pool#)
     (try
      (let [~res-name (:resource resource#)]
	(do ~@body))
      (finally
       (reset! ~ref-pool (release-resource (deref ~ref-pool) resource#))))))
