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

    // Note: this is duplicated by the dynamic var *actor*, but it's a good idea to keep both:
    // *actor* should be kept as it's part of the public API;
    // while this one is faster to access internally (it does not involve a look-up in the thread frame).
    private static final ThreadLocal<Actor> CURRENT_ACTOR = new ThreadLocal<Actor>();

    private IFn behavior; // current behavior, used when actor starts, and re-used for (become :same args)
    private ISeq args; // arguments to pass to next call to behavior
    private final Inbox inbox = new Inbox();

    static class Message {
        // TODO sender is not used anywhere
        final Actor sender;
        final Actor receiver;
        final ISeq args;

        public Message(Actor sender, Actor receiver, ISeq args) {
            this.sender = sender;
            this.receiver = receiver;
            this.args = args;
        }

    }

    public Actor(IFn behavior, ISeq args) {
        become(behavior, args);
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

    public static void doBecome(IFn behavior, ISeq args) {
        Actor.getEx().become(behavior, args);
    }

    private void become(IFn behavior, ISeq args) {
        // Note: this always runs in the current actor (so we're never setting the behavior and args of an actor running
        // in another thread), as become is only called by doBecome on the current actor.
        if (behavior != null) // We allow (become :same args), which re-uses the old behavior
            this.behavior = behavior;
        this.args = args;
    }

    public void enqueue(Actor sender, ISeq args) throws InterruptedException {
        Message message = new Message(sender, this, args);
        inbox.enqueue(message);
    }

    public void run() {
        CURRENT_ACTOR.set(this);

        // Bind *actor* to this
        Map<Var, Object> m = new HashMap<Var, Object>();
        m.put(RT.ACTOR, this);
        IPersistentMap bindings = PersistentArrayMap.create(m);
        Var.pushThreadBindings(bindings);

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
                IFn behaviorInstance = (IFn) behavior.applyTo(args);
                behaviorInstance.applyTo(message.args);
            }
        } finally {
            Var.popThreadBindings();
        }
    }

}
