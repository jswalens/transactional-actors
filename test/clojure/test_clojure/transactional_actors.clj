;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;; Author: Janwillem Swalens

(ns clojure.test-clojure.transactional-actors
  (:use clojure.test))

;(def logger (agent nil))
(defn log [& args]
  nil)
;(defn log [& args]
;  (send logger (fn [_] (apply println args))))

; TODO: remove most/all Thread/sleep's from the tests (using promises?).

; COUNTER: uses transactions
; Does not require special changes for the combination of actors and transactions.
(deftest counter
  (testing "COUNTER - WORKS AS EXPECTED"
    (let [sum (ref 0)
          counter
          (behavior
            [i]
            [msg & args]
            (case msg
              ;:get
              ;(dosync
              ;  (log "my sum:" i "- total sum:" @sum))
              :inc
              (dosync
                (log "my sum:" i "+ 1 - total sum:" @sum "+ 1")
                (alter sum + 1)
                (become :same (+ i 1)))
              :add
              (let [j (first args)]
                (dosync
                  (log "my sum:" i "+" j "- total sum:" @sum "+" j)
                  (alter sum + j)
                  (become :same (+ i j))))))
          counter1 (spawn counter 0)
          counter2 (spawn counter 0)]
      (send counter1 :inc)
      (send counter2 :inc)
      (send counter1 :add 5)
      ;(send counter1 :get)
      (send counter1 :add 4)
      (send counter2 :add 9)
      ;(send counter2 :get)
      ;(send counter1 :get)
      (Thread/sleep 100)
      ;(send counter1 :get) (Thread/sleep 10)
      ;(send counter2 :get) (Thread/sleep 10)
      (is (= 20 @sum))))) ; this succeeds

; SUMMER: send in transaction
; If send is not reverted when a transaction is reverted, its effects remain visible after a rollback.
(deftest summer-send
  (testing "SUMMER - PROBLEM WITH SEND"
    (let [contentious-ref (ref 0)
          check-success? (atom :unknown)
          receiver
          (behavior
            [i]
            [msg]
            (case msg
              ;:get
              ;(log "received" i "messages")
              :check
              (do
                (is (= 100 i))
                (reset! check-success? (= 100 i)))
              :inc
              (become :same (inc i))))
          receiver-actor (spawn receiver 0)
          sender
          (behavior
            []
            []
            (dosync
              (send receiver-actor :inc)
              (alter contentious-ref inc)))
          senders (doall (repeatedly 100 #(spawn sender)))]
      (is (= 100 (count senders)))
      (doseq [s senders]
        (send s))
      (Thread/sleep 1000)
      ;(send receiver-actor :get) (Thread/sleep 10)
      (log "expected 100 messages received")
      (send receiver-actor :check) (Thread/sleep 10)
      (is (= true @check-success?))))) ; ensure that test actually executed

; SUMMER: spawn in transaction
; If spawn is not reverted when a transaction is reverted, the new actor remainn active after a rollback.
(deftest summer-spawn
  (testing "SUMMER - PROBLEM WITH SPAWN"
    (let [sum (ref 0)
          contentious-ref (ref 0)
          counter
          (behavior
            [i]
            [msg & args]
            (case msg
              ;:get
              ;(dosync
              ;  (log "my sum:" i "- total sum:" @sum))
              :inc
              (dosync
                (log "my sum:" i "+ 1 - total sum:" @sum "+ 1")
                (alter sum + 1)
                (become :same (+ i 1)))
              :add
              (let [j (first args)]
                (dosync
                  (log "my sum:" i "+" j "- total sum:" @sum "+" j)
                  (alter sum + j)
                  (become :same (+ i j))))))
          spawner
          (behavior
            []
            []
            (dosync
              (send (spawn counter 0) :inc)
              (alter contentious-ref inc)))
          spawners (doall (repeatedly 100 #(spawn spawner)))]
      (is (= 100 (count spawners)))
      (doseq [s spawners]
        (send s))
      (Thread/sleep 1000)
      (is (= 100 @sum)))))

; FLAGGER: become in transaction
; If become is not reverted when a transaction is reverted, the behavior is changed even after a rollback.
(deftest flagger-become
  (testing "FLAGGER - PROBLEM WITH BECOME"
    (let [one-flag-set? (ref false)
          flags-at-the-end (agent [])
          flagger
          (behavior
            [flag]
            [msg]
            (case msg
              :set-flag
              (dosync
                (when-not @one-flag-set?
                  (become :same true)
                  (ref-set one-flag-set? true)))
                ; else: flag stays false, one-flag-set? stays true
              :read-flag
              (send flags-at-the-end conj flag))) ; NOT send but to agent
          flaggers (doall (repeatedly 100 #(spawn flagger false)))]
      (is (= 100 (count flaggers)))
      (doseq [f flaggers]
        (send f :set-flag))
      (Thread/sleep 1000)
      (doseq [f flaggers]
        (send f :read-flag))
      (Thread/sleep 1000)
      (log "flags at the end:" @flags-at-the-end)
      (let [n (count @flags-at-the-end)
            t (count (filter true? @flags-at-the-end))
            f (count (filter false? @flags-at-the-end))]
        (log "true:" t "/" n "- false:" f "/" n)
        (is (= 100 n))
        (is (= 1 t))
        (is (= (- n 1) f))))))
