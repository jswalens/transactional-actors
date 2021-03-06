/**
 * Copyright (c) Rich Hickey. All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/

/* Janwillem Mar, 2017 */

package clojure.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

// TODO: garbage collection of actors
public class Actor implements Runnable {

    private static class AbortEx extends Error{
    }
    private static final AbortEx abortex = new AbortEx();

    static void abortIfDependencyAborted() throws AbortEx, InterruptedException {
        Actor current = CURRENT_ACTOR.get();
        if (current == null)
            return;
        if (!current.tentative())
            return;
        current.dependency.waitUntilFinished();
        if (!current.dependency.committed())
            throw Actor.abortex;
    }

    static class Inbox {
        private final LinkedBlockingDeque<Message> q = new LinkedBlockingDeque<Message>();

        private Inbox() { }

        void enqueue(Message message) throws InterruptedException {
            q.put(message);
        }

        Message take() throws InterruptedException {
            return q.take();
        }
    }

    static class Behavior {
        IFn body;
        ISeq args; // arguments to pass to call to body

        public Behavior(IFn body, ISeq args) {
            this.body = body;
            this.args = args;
        }

        public IFn apply() {
            return (IFn) body.applyTo(args);
        }
    }

    // Note: this is duplicated by the dynamic var *actor*, but it's a good idea to keep both:
    // *actor* should be kept as it's part of the public API;
    // while this one is faster to access internally (it does not involve a look-up in the thread frame).
    private static final ThreadLocal<Actor> CURRENT_ACTOR = new ThreadLocal<Actor>();

    private Behavior behavior;
    private final Inbox inbox = new Inbox();

    private LockingTransaction.Info dependency = null;
    private List<Actor> spawned = new ArrayList<Actor>();
    private Behavior oldBehavior = null;

    static class Message {
        final Actor receiver;
        final ISeq args;
        final LockingTransaction.Info dependency; // can be null

        public Message(Actor receiver, ISeq args) {
            this(receiver, args, null);
        }

        public Message(Actor receiver, ISeq args, LockingTransaction.Info dependency) {
            this.receiver = receiver;
            this.args = args;
            this.dependency = dependency;
        }

    }

    public Actor(IFn behaviorBody, ISeq behaviorArgs) {
        behavior = new Behavior(behaviorBody, behaviorArgs);
    }

    static Actor getRunning() {
        return CURRENT_ACTOR.get();
    }

    static Actor getEx(){
        Actor a = CURRENT_ACTOR.get();
        if(a == null)
            throw new IllegalStateException("No actor running");
        return a;
    }

    public boolean tentative() {
        return dependency != null;
    }

    public static Actor doSpawn(IFn behaviorBody, ISeq behaviorArgs) {
        Actor actor = new Actor(behaviorBody, behaviorArgs);
        Actor.start(actor); // might be delayed
        return actor;
    }

    public static void start(Actor actor) {
        // TODO: what if transaction committed successfully (so dependency committed): now we still add to spawned (2nd
        // case), but we could immediately execute (how does this affect the order?).
        if (LockingTransaction.getRunning() != null)
            // tx running: keep in tx
            LockingTransaction.getEx().spawnActor(actor);
        else if (CURRENT_ACTOR.get() != null && CURRENT_ACTOR.get().tentative())
            // no tx running, but tentative turn: keep in actor
            CURRENT_ACTOR.get().spawned.add(actor);
        else
            // else: do immediately
            Agent.soloExecutor.submit(actor);
    }

    public static void doBecome(IFn behaviorBody, ISeq behaviorArgs) {
        Behavior behavior = new Behavior(behaviorBody, behaviorArgs);
        if (LockingTransaction.getRunning() != null)
            // tx running: only persist become in tx
            LockingTransaction.getEx().become(behavior);
        else
            // else: become in actor
            Actor.getEx().become(behavior);
    }

    void become(Behavior newBehavior) {
        // Note: this always runs in the current actor (we're never setting the behavior of an actor running in another
        // thread), as become is only called by doBecome on the current actor.
        if (newBehavior.body == null) // We allow (become :same|nil args), which re-uses the old behavior
            newBehavior.body = behavior.body;
        behavior = newBehavior;
    }

    public static void doEnqueue(Actor receiver, ISeq args) throws InterruptedException {
        LockingTransaction.Info dependency = null;
        if (LockingTransaction.getRunning() != null)
            // tx running: tx = dependency
            dependency = LockingTransaction.getEx().info;
        else if (getRunning() != null && getRunning().tentative())
            // no tx running, but tentative turn: transitive dependency
            dependency = getRunning().dependency;
        // else: no dependency
        Message message = new Message(receiver, args, dependency);
        receiver.enqueue(message);
    }

    private void enqueue(Message message) throws InterruptedException {
        inbox.enqueue(message);
    }

    public void run() {
        CURRENT_ACTOR.set(this);

        // Create bindings map that binds *actor* to this. Used below.
        Map<Var, Object> m = new HashMap<Var, Object>();
        m.put(RT.ACTOR, this);
        IPersistentMap bindings = PersistentArrayMap.create(m);

        try {
            while (true) {
                try {
                    // TODO: end actor when it is no longer needed (garbage collection of actors)
                    Message message = inbox.take();

                    // If message has a dependency, this is a tentative turn
                    if (message.dependency != null) {
                        dependency = message.dependency;
                        oldBehavior = behavior;
                    }

                    try {
                        IFn behaviorInstance = (IFn) behavior.apply();

                        // Bind *actor* to this
                        // Note: the behavior is encapsulated in a "binding-conveyor", hence, the first action when
                        // creating the behaviorInstance above is resetting its frame to the bindings that were present
                        // when the behavior was defined. Here, we extend those bindings with one for *actor*.
                        Var.pushThreadBindings(bindings);

                        behaviorInstance.applyTo(message.args);
                    } catch (AbortEx e) {
                        throw e;
                        // Below, catch everything except AbortEx
                    } catch (Throwable e) {
                        // TODO: graceful error handling. See error handling in Agent for a better solution.
                        System.out.println("uncaught exception in actor: " + e.getMessage());
                    }

                    abortIfDependencyAborted();

                    dependency = null;
                    for (Actor actor : spawned) {
                        Actor.start(actor);
                    }
                } catch (AbortEx e) {
                    behavior = oldBehavior;
                } finally {
                    dependency = null;
                    oldBehavior = null;
                    spawned.clear();
                }
            }
    } catch (InterruptedException ex) {
            // interrupt thread
    } finally {
            Var.popThreadBindings();
        }
    }

}
