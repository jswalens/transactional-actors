;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;; Author: Janwillem Swalens

(ns clojure.test-clojure.actors
  (:use clojure.test)
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(deftest counter
  (let [counter
          (behavior [i]
            [:inc]
              (become :self [(+ i 1)])
            [:add j]
              (become :self [(+ i j)])
            [:get p]
              (deliver p i))
        test (fn [actor n]
               (let [p (promise)]
                 (send actor :get p)
                 (is (= @p n))))
        counter1 (spawn counter [0])
        counter2 (spawn counter [0])]
    (send counter1 :inc)
    (send counter2 :inc)
    (send counter1 :add 5)
    (send counter1 :add 4)
    (send counter2 :add 9)
    (test counter1 10)
    (test counter2 10)))

(deftest spawn-test
  (let [beh (behavior []
              [:ok]
                true)
        act (spawn beh [])]
    (is (some? act))))

(deftest send-test
  (let [beh (behavior []
              [:deliver p]
                (deliver p true))
        act (spawn beh [])
        p   (promise)]
    (send act :deliver p)
    (is (true? @p))))

(deftest become-test
  (let [beh2 (behavior []
               [:deliver p]
                 (deliver p 2))
        beh1 (behavior []
               [:deliver p]
                 (deliver p 1)
               [:become]
                 (become beh2 []))
        act (spawn beh1 [])
        p1  (promise)
        p2  (promise)]
    (send act :deliver p1)
    (is (= @p1 1))
    (send act :become)
    (send act :deliver p2)
    (is (= @p2 2))))
