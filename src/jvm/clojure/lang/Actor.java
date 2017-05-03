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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;

// TODO: garbage collection of actors
public class Actor implements Runnable {

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
        IFn behaviorBody;
        ISeq args; // arguments to pass to call to behaviorBody

        public Behavior(IFn behaviorBody, ISeq args) {
            this.behaviorBody = behaviorBody;
            this.args = args;
        }

        public IFn apply() {
            return (IFn) behaviorBody.applyTo(args);
        }
    }

    // Note: this is duplicated by the dynamic var *actor*, but it's a good idea to keep both:
    // *actor* should be kept as it's part of the public API;
    // while this one is faster to access internally (it does not involve a look-up in the thread frame).
    private static final ThreadLocal<Actor> CURRENT_ACTOR = new ThreadLocal<Actor>();

    private Behavior behavior;
    private final Inbox inbox = new Inbox();

    static class Message {
        final Actor receiver;
        final ISeq args;
        final LockingTransaction dependency; // can be null

        public Message(Actor receiver, ISeq args) {
            this(receiver, args, null);
        }

        public Message(Actor receiver, ISeq args, LockingTransaction dependency) {
            this.receiver = receiver;
            this.args = args;
            this.dependency = dependency;
        }

    }

    public Actor(IFn behaviorBody, ISeq args) {
        behavior = new Behavior(behaviorBody, args);
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

    public void start() {
        LockingTransaction tx = LockingTransaction.getRunning();
        if (tx != null)
            tx.spawnActor(this);
        else
            Agent.soloExecutor.submit(this);
    }

    public static void doBecome(IFn behaviorBody, ISeq args) {
        Behavior behavior = new Behavior(behaviorBody, args);
        LockingTransaction tx = LockingTransaction.getRunning();
        if (tx != null)
            tx.become(behavior);
        else
            Actor.getEx().become(behavior);
    }

    void become(Behavior newBehavior) {
        // Note: this always runs in the current actor (we're never setting the behavior of an actor running in another
        // thread), as become is only called by doBecome on the current actor.
        if (newBehavior.behaviorBody == null) // We allow (become :same|nil args), which re-uses the old behavior
            newBehavior.behaviorBody = this.behavior.behaviorBody;
        this.behavior = newBehavior;
    }

    public static void doEnqueue(Actor receiver, ISeq args) throws InterruptedException {
        LockingTransaction tx = LockingTransaction.getRunning();
        Message message = new Message(receiver, args, tx);
        if (tx != null)
            tx.sendMessage(message);
        else
            receiver.enqueue(message);
    }

    void enqueue(Message message) throws InterruptedException {
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
                // TODO: end actor when it is no longer needed (garbage collection of actors)
                Message message;
                try {
                    message = inbox.take();
                } catch (InterruptedException ex) {
                    return;
                }
                // TODO: graceful error handling. Currently, if an exception is thrown we don't handle it, it simply
                // aborts the actor. See error handling in Agent for a better solution.
                IFn behaviorInstance = (IFn) behavior.apply();

                // Bind *actor* to this
                // Note: the behavior is encapsulated in a "binding-conveyor", hence, the first action when creating the
                // behaviorInstance above is resetting its frame to the bindings that were present when the behavior was
                // defined. Here, we extend those bindings with one for *actor*.
                Var.pushThreadBindings(bindings);

                behaviorInstance.applyTo(message.args);
            }
        } finally {
            Var.popThreadBindings();
        }
    }

}
