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

    private static final ThreadLocal<Actor> CURRENT_ACTOR = new ThreadLocal<Actor>();

    private IFn oldBehavior; // re-used for (become :same args)
    private IFn behaviorInstance; // behavior applied to its arguments (state)
    private final Inbox inbox = new Inbox();

    static class Message {
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
        // TODO: should we check whether we're actually running in this actor?
        if (behavior == null) // When doing (become :same args), we re-use the old behavior
            behavior = oldBehavior;
        behaviorInstance = (IFn) behavior.applyTo(args);
        oldBehavior = behavior;
    }

    public void enqueue(Actor sender, ISeq args) throws InterruptedException {
        Message message = new Message(sender, this, args);
        inbox.enqueue(message);
    }

    public void run() {
        CURRENT_ACTOR.set(this);
        while (true) {
            // TODO: end actor when it is no longer needed (garbage collection of actors)
            Message message;
            try {
                message = inbox.take();
            } catch (InterruptedException ex) {
                return;
            }
            behaviorInstance.applyTo(message.args);
        }
    }

}
