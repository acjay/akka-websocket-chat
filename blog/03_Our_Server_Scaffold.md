# Our Server Scaffold

Alright, now let's build something. As mentioned in the last piece:

> I'm going to zoom in on a common category of use cases of WebSockets, instead of the full generality. Often, WebSockets are used to model a combination of the client command -> server response paradigm with push messages from a server. They also often have implicit or explict state over the life of the connection. This could include protocol state, like authentication status, or application state, like mutable connection options. HTTP has evolved mechanisms and patterns for all of these things, through features like headers, cookies, and server-sent events (SSE). WebSocket doesn't standardize these protocol concerns in any way, so you have to build this yourself. We'll walk through my attempt to build a basic framework for a "command and push" WebSocket server, and use it to power a chat app.

So, we've got to pack all of these protocol concerns into the `Flow[Message, Message, NotUsed]` expected by Akka HTTP _and_ still actually implement business logic. And as great as Akka Streams is, one thing we _don't_ want to do is have to express our business logic using Akka Streams primitives. So we're going to need some sort of layered architecture.

Sounds tricky, right? It's tricky enough that I never figured it out at my last job. But I think that starting from scratch and knowing what I know, I can pull it off and walk you through it.

At a high level, what we're going to do is break this down into layers. We want something that looks kind of like:

```
Transport-agnostic business logic
-> Application-specific, library-agnostic WebSocket protocol
-> Application-agnostic WebSocket scaffold (in Akka Streams)
-> Akka HTTP
```

Akka HTTP already exists, so we're already 25% of the way there. Our goal main goal at this stage is to write a scaffold that allows our WebSocket protocol to be specified _without_ having to build the application-specific aspects of that protocol directly in Akka Streams.

_Why bother?_ You ask.

Well, as nice is Akka Streams is, it is very nuanced. As developers, we are accustomed to writing server logic in the form of ordinary methods and functions, not streams. This will make our protocol itself much easier to work on. It will be testable independently from the underlying Akka framework. If we decide we want to use Play's WebSockets instead of Akka HTTP's, or just benchmark different solutions, we can write different adapters.

Some of this is arguably [YAGNI](https://en.wikipedia.org/wiki/You_aren%27t_gonna_need_it). Maybe we'll never migrate our app. Still, breaking a system down into simple, cohesive, composable parts is almost always a win in my experience.

## Separate the transport logic from the business logic

In general, as a developer I try to express my business logic in the form of _async functions over domain-specific data structures_. I'll inject data access objects for communication with the outside world, but the business logic itself won't directly couple to libaries or data stores.

Applying this principle to a WebSocket server, I don't want my business logi dealing with `Flow`s, or any other complex library structures. That's going to be much more difficult to reason about and test. This means I need to write an adapter from Akka HTTP's `Flow`-based API to something I can plug my simple business logic functions into.

For this project, I have created an adapter called `CommandAndPushWebSocketHandler`. It builds a scaffold for the sort of WebSocket connection handler I suspect many people need in their real-time apps. It supports:

- Pluggable translation between the wire format and Scala data structures.
- Command-response roundtrips from client to server, similar to HTTP or RPC.
- Server-push messages to the client.
- Session data storage, along with state changes in response to client commands or server events.
- Termination of the connection, via an imperative hook.

`CommandAndPushWebSocketHandler` is a `trait`, with abstract methods for each of these concerns. It also has abstract types in the signatures of those methods, wrapped in standard library containers like `Option` and `Future` to allow for conditional responses and asynchronicity. Notably, the signatures of these abstract methods do not include any Akka Streams primitives. To use it, you simply extend the trait with your own implementation, which plugs in concrete implementations and types. 

It's a simple framework, but it allows for all of the protocol complexity I recall us evolving when I worked at Artsy. That should be more than enough power for our chat service. Its main bit of cleverness is the concept of an _action_. The idea is that client commands and server events don't necessarily directly produce responses back to the client. Instead, they produce actions. These actions are processed in a central handler, which can have the effects of sending a message to the client and/or creating a session state transition. Actions also give us a way to model universal connection events, like the start and end of the connection itself. These can likewise trigger messages to the client and state transitions.

## Breakdown of our `handler`

The heart of our scaffold is a property called `handler`, which is meant to be passed to Akka HTTP's `handleWebSocketMessages`. We build it up piece by piece using Akka Streams's flow DSL in one long expression. I'm assuming very little familiarity with this library, so I'm going to break this down line-by-line. Within this expression, we'll be calling out to the abstract methods that the application logic will override to implement its protocol. 

Here we go!

1. We start with `Flow[Message]`. This is one of the ways you can construct a `Flow`, and it's the simplest way. It creates an "identity `Flow`", which simply passes its inputs unchanged. That could actually be a echo server, if we stopped there, but this would be pretty silly!

   `Flow[Message]` may seem rather useless, but it's actually pretty handy. This is because of the methods `Flow` provides, which let us modify this behavior, building up complexity step-by-step, modifying the type of the resulting `Flow` along the way.
  
   As of this step, our expression is of type `Flow[Message, Message, NotUsed]`. We won't pay much attention to that third arguments, so as we keep score, we'll simply omit it and say we're working with a `Flow[Message, Message]`.

2. We call `.mapAsync(1)(deserialize)`. `deserialize` is an abstract method defined as `def deserialize(command: Message): Future[Cmd]`, where the type `Cmd` is also abstract. In many cases, the implementation will convert JSON to a Scala object of some kind. But that's up to the application protocol.

   We'll see a lot more of `mapAsync`. It takes a function that returns a `Future`. It calls the function and when the `Future` resolves, the resulting value is emited. You might think of it as _flattening_ the future into our stream. So now we have a `Flow[Message, Cmd]`.

   (The `1` we hardcoded specifies the parallelism of `mapAsync`. It will preserve order if you increase this number for this use case, but we have no need to do this, so we'll always use `1`.)

   Note that the protocol is required to produce some kind of `Cmd` in all cases. `Cmd` should therefore have a subtype for representing deserialization errors. I originally used `Option` here, but it occurred to me that in a general-purpose scaffold, it's better to provide _just one way_ to do something than two roughly equal approaches.

3. Inside the next `mapAsync`, we do something a little more nuanced. We want to let the application process the command, and also provide the current state. We use a `for`-comprehensions to compose the async operations of getting the state and processing the command, and then we wrap the result in a `Responding` object. This is a subclass of `Action` and its how `processAction` will differentiate between data arrving from different sources.

   `getState` is an abstract method that simply returns a `Future` of the current session data. It's possible that the implementation of this is synchronous, but as a generic scaffold, we don't want to assume so. The return value is of the abstract type `Sess`, which allows the application logic to define the shape of its state data.

   `processCommand` is the first place where we hand off control to application logic to potentially do some serious business logic. Most likely, the implementation of this method will roughly equivalent to the routing logic in an HTTP server, dispatching commands and queries to business logic. But commands will likely also include protocol concerns, like authentication.

   It returns an abstract type called `Res`, which represents the result of the command. Unlike an HTTP server, this might not _only_ be the data returned to the user. It should also contain any other metadata `processAction` needs to do things like session state transitions. It might also include diagnostic data for metrics. It's all up to the implementation.

   At this point, the entire expression we've built up is of type `Flow[Message, (Action, Sess)]` <sup>[1](#1)</sup>.

4. And now for my favorite part. So far, our `Flow` has just been a straight pipeline, and not that much different from a function. `merge` brings in another channel of data froma given `Source`. The result of `merge` is _just another `Flow`_.

   Let's process this for a second. This is a little weird, because our `Flow` isn't just a straight line anymore; it's got these _other_ values merging in from the side. The trick here to understanding why we're still just a simple one-way `Flow` is to think about the values that _aren't_ under our control. We're taking responsibility for these push messages, so they live inside the overall `Flow`. When we hand it over to Akka HTTP, there's just one free input and one free output left over, which are used for communication with the client.

   There are a number of different ways Akka HTTP let's us create a `Source` from some source of data. We shouldn't make that decision on behalf the application logic in the scaffold. So, we'll leave that abstract too (sensing a pattern here?), which is our `val pushMessageSource: PushMessageSource[Push]`. `PushMessageSource` is defined in our companion object as trait with one abstract method: `val source: Source[Push]`. Different `Source` constructors have different methods of introducing elements, so it's up to the application logic to decide which implmentation to use, and how to wire up data to the input for the source. A couple implementations are provided. `processAction` is provided access to the `PushMessageSource` via the `ConnectionControl` object, which bundles up references that give imperative control over the connection itself to the application logic.

   At this point, we still have `Flow[Message, (Action, Sess)]`<sup>[2](#2)</sup>.

5. `map(Telling(_))` wraps any push messages that arrive so that the can be differentiated from other events in the system. `mapAsync(1)(withState)` augments this data with the session state to match the shape of data coming out of the command branch of the flow.

6. `.prepend(Source.single(Starting).mapAsync(1)(withState))` is a bigger chunk, but knowing what we know from the last step, it should be easy to analyze. `Starting` is another action we've defined, serving as the signal for `processAction` to do any initial effects, such as sending an auth challenge message to the client. `prepend` is like `merge`, except that rather than having unspecified order, `prepend` fully exhausts its source before the flow it is attached to is allowed to emit anything. As with the previous step, we augment with the session state.

7. We want our application logic to be able to terminate the connection for any reason it sees fit. Akka Streams provides a utility called [`KillSwitch`]() to do just that.

   To explain how this works, I have to introduce an Akka Streams concept called _materialization_. A runnable graph is, in a sense, sealed off from the outside world. Materlialized values provide hooks for data and control to get in and out.

   There are two ways to use materialized values. In the docs, they talk about running a graph, which returns its materialized values. As you build your graph from its elementary parts, there are alternative combinators to all the ones we've seen that let you control what the final materialized value will be. You could propagate the functions provided by a `KillSwitch`, for example. Or a queue that provides values for a `Source`.

   Here, I use a different tactic. `mapMaterializedValue` is a tool for managing your materialized values as you compose your full graph. It will run once, when the stream is materialized. Here, I take advantage of that fact to stash a reference to the materialized value in an instance variable. Note that this is only viable if I know that every connection is going to have a fresh instance of `CommandAndPushWebSocketHandler` and that Akka HTTP is only going to materialize that instance's `handler` one time. It would be better for me if Akka Stream provided an `onMaterliazation` callback, since `map` suggests the purpose is to manipulate the materialized value in a pure way. I suspect the fact this doesn't exists means this pattern isn't recommended, but hey, it works for me.

   Application code can trigger the killswitch via methods on `ConnectionControl`, which is provided as an argument to `processAction`. Due to the placement of the killswitch, it will interrupt any actions that haven't already been processed. One slick aspect of Akka Streams is that using a parallelism of 1 on `mapAsync` means that no buffering will happen and backpressure propagates upstream. This means that the kill switch should terminate the connection as soon as it is triggered.

8. `.concat(Source.single(Ending).mapAsync(1)(withState))` is almost the same as step 6, except that `concat` insert the elements from its source _after_ the flow it is attached to completes. If the killswitch is triggered with `shutdownConnection()`, then `processAction` will have one last chance to do anything related to closing the connection. It is worth noting that no context about why the connection was closed will be attached to the `Ending` action, so the use cases for this might be a little limited.

9. At this point, we've got a `Flow[Message, (Action, Sess)]`, and all possible actions have been merged in. Yet another `mapAsync` is used here, for the purposes of invoking the application logic's `processAction` method, with the action, state, and the connection control object. That method should produce output data and the next state value, if the connection state has changed. The result of this stage is `Flow[Message, Option[Out]]`.

10. `.mapConcat(_.toList)` is the idiom for unwrapping `Option`s flowing through a stream, much as `mapAsync` does for `Future`s. This simply results in `Flow[Message, Out]`.

11. Next, we call `.mapAsync(1)(serialize)`. As the mirror image to `deserialize`, `serialize` is an abstract method defined as `def serialize(output: Out): Future[Message]`. This means we've come full circle, and the final type of our long `handler` expression is `Flow[Message, Message]`. The types are the same as what we started with, but the behavior can be a whole lot more interesting. This scaffold provides some constraints, but the application logic that extends `CommandAndPushWebSocketHandler` has quite a bit of leeway in what it can model.

> <a target="1"><sup>1</sup></a> To be exact, we now have a `Flow[Message, (CommandAndPushWebSocketHandler#Responding, Sess), NotUsed]`. We can think of the first parameter as being `CommandAndPushWebSocketHandler#Action` because that's a supertype of `CommandAndPushWebSocketHandler#Responding`.
> 
> The `CommandAndPushWebSocketHandler#` prefix on `Responding` indicates that the type `Responding` is _specific_ to every instance of `CommandAndPushWebSocketHandler`. This is an important detail because `Responding` has a property `commandResult: Res`, and `Res` is an abstract member of the trait. In other words, the `Responding` class of any two instances of `CommandAndPushWebSocketHandler` have no formal relationship to one another. They just happen to have the same parameter name. There are techniques to establish a formal relationship unifying all `Response` classes, but that is not helpful in our case, so we will not do this.

> <a target="2"><sup>2</sup></a> In reality, only now do we have `Flow[Message, (CommandAndPushWebSocketHandler#Action, Sess), NotUsed]`. The `Flow` in the previous step was `Flow[Message, (CommandAndPushWebSocketHandler#Responding, Sess), NotUsed]` and our `Source` we merged in is `Flow[Message, (CommandAndPushWebSocketHandler#Telling, Sess), NotUsed]`. `merge` results in the _least upper bound_ of these two types, which is `Flow[Message, (CommandAndPushWebSocketHandler#Action, Sess), NotUsed]`, because `Action` is the superclass of both `Responding` and `Telling`.
