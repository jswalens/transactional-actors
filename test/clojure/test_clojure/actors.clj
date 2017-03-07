;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;; Author: Janwillem Swalens

(ns clojure.test-clojure.actors
  (:use clojure.test))

(def send send-actor) ; TODO: remove this later

(deftest simple
  (let [p (promise)
        b (behavior [] (receive [] (deliver p true)))
        a (spawn b)]
    (send a)
    (is (deref p 5000 false))
    (is (realized? p) "Promise not delivered after 5000 ms.")))

(deftest two-messages
  (let [p1 (promise)
        p2 (promise)
        b (behavior [] (receive [p] (deliver p true)))
        a (spawn b)]
    (send a p1)
    (send a p2)
    (is (deref p1 3000 false))
    (is (realized? p1) "Promise 1 not delivered after 3000 ms.")
    (is (deref p2 3000 false))
    (is (realized? p2) "Promise 2 not delivered after 3000 ms.")))

(deftest counter
  (let [counter
          (behavior
            [i]
            (receive
              [msg & args]
              (case msg
                :inc (become :same (+ i 1))
                :add (become :same (+ i (first args)))
                :get (deliver (first args) i))))
          #_(behavior [i]
            [:inc]
              (become :same [(+ i 1)])
            [:add j]
              (become :same [(+ i j)])
            [:get p]
              (deliver p i))
        test (fn [actor n]
               (let [p (promise)]
                 (send actor :get p)
                 (is (= (deref p 5000 false) n))))
        counter1 (spawn counter 0)
        counter2 (spawn counter 0)]
    (send counter1 :inc)
    (send counter2 :inc)
    (send counter1 :add 5)
    (send counter1 :add 4)
    (send counter2 :add 9)
    (test counter1 10)
    (test counter2 10)))

(deftest spawn-test
  (let [beh (behavior
              []
              (receive
                [msg & args]
                true))
            #_(behavior []
              [:ok]
                true)
        act (spawn beh)]
    (is (some? act))))

(deftest send-test
  (let [beh (behavior
              []
              (receive
                [msg & args]
                (deliver (first args) true)))
            #_(behavior []
              [:deliver p]
                (deliver p true))
        act (spawn beh)
        p   (promise)]
    (send act :deliver p)
    (is (deref p 5000 false))))

(deftest become-test
  (let [beh2 (behavior
               []
               (receive
                 [msg & args]
                 (deliver (first args) 2)))
             #_(behavior []
               [:deliver p]
                 (deliver p 2))
        beh1 (behavior
               []
               (receive
                 [msg & args]
                 (case msg
                   :deliver (deliver (first args) 1)
                   :become  (become beh2))))
             #_(behavior []
               [:deliver p]
                 (deliver p 1)
               [:become]
                 (become beh2 []))
        act (spawn beh1)
        p1  (promise)
        p2  (promise)]
    (send act :deliver p1)
    (is (= (deref p1 5000 false) 1))
    (send act :become)
    (send act :deliver p2)
    (is (= (deref p2 5000 false) 2))))
