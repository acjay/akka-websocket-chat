# WebSockets and Akka HTTP

What really inspired this project was my perosnal experience architecting [Artsy's live auction experience](http://artsy.github.io/blog/2016/08/09/the-tech-behind-live-auction-integration/). I made an early decision to bet on the relatively new Akka HTTP library, under the assumption that the Akka project's reputation for performance and reliability would serve the needs of that product well. While this turned out to be true, there were not a whole lot of great examples to follow. You see, [Akka HTTP's WebSocket support](https://doc.akka.io/docs/akka-http/current/server-side/websocket-support.html) is built using Akka Streams, which was also a brand new library at the time. Akka Streams is a [Reactive Streams](http://www.reactive-streams.org/) implementation built on top of [Akka](https://doc.akka.io/docs/akka/current/guide/introduction.html), and within Akka Streams alone, there are internal layers of abstraction to understand.

The result is that it was and is difficult to figure out the minimal set of pieces needed to implement a WebSocket service. Most of the tutorials I read had two major problems. First, their code access low-level APIs than necessary for this type of project. Second, they tightly couple the logic of building a WebSocket protocol with the business logic of the demo app.

When we built live auctions, we managed to solve the first problem. But over the two years I continued working on that project after it launched, I was never particularly pleased that we weren't able to fully unknit the business logic from the server logic.

## What is a WebSocket?

It's worth spending a couple paragraphs on this question. Most web services are built using HTTP. In classic HTTP, all communication between the client and the server starts with a client request and results in a server response. It's a lot like calling a function in a typical programming language. The client request serves as the arguments and the server response serves as the return value. In a lot of web application frameworks, you implement your request handlers as functions, literally.

WebSocket is different. The client requests a connection, but once established, both the client and the server can send unidirectional messages. In fact, those messages can be streams of undefined length. There's no built-in concept of a response. It's a very unconstrained model, and it's a lot more similar to raw socket programming than HTTP.

For this reason, WebSocket server libraries tend to be very sparse. You often send messages imperatively and register a callback that is invoked for messages received. Akka HTTP models it a bit differently, as we'll see below, but it's just as vaguely specified.

Eventually, I'm going to zoom in on a common category of use cases of WebSockets, instead of the full generality. Often, WebSockets are used to model a combination of the client command -> server response paradigm with push messages from a server. They also often have implicit or explict state over the life of the connection. This could include protocol state, like authentication status, or application state, like mutable connection options. HTTP has evolved mechanisms and patterns for all of these things, through features like headers, cookies, and server-sent events (SSE). WebSocket doesn't standardize these protocol concerns in any way, so you have to build this yourself. We'll walk through my attempt to build a basic framework for a "command and push" WebSocket server, and use it to power a chat app.

But first, we have to talk about the server framework I'll be using.

## Akka HTTP's WebSocket support

In Akka HTTP, an entire WebSocket server is implemented as one Akka Streams component, known as a `Flow`. This is a striking contrast with [Akka HTTP's high-level API](https://doc.akka.io/docs/akka-http/current/routing-dsl/index.html), in which you build up your server by composing dozens of individual directives. (Although, notably, under the hood, [this is also built on Akka Streams](https://doc.akka.io/docs/akka-http/current/server-side/low-level-api.html).)

### What is a Flow?

A `Flow` is a generalization of an ordinary function. So let's start by talking about Scala's modeling of unary functions, `Function1`. `Function1` is formally defined as `Function1[T1, R]`<sup>[1](#1)</sup>, which has a generic input parameter `T1` and a return type `R`. Any _specific_ `Function1` instance will have concrete classes for those types. There are `Function2`, `Function3` all the way up to `Function22` (don't ask), but technically, all you really need is `Function1`, and you could use tuples to simulate greater numbers of parameters. All the other variations exist for convenience.

`Flow` is defined as `Flow[In, Out, Mat]` The first type parameter `In` is the input. Unlike `Function*`, you _have_ to use tuples or ADTs to bundle up data to simulate multiple arguments, but no biggie. The second type parameter, `Out`, is analogous to the return type of a function. The third type parameter, `Mat`, isn't important yet, so we'll just skip it.

The key difference between `Flow` and `Function1` is that a function is called at a specific time and evaluate to its return a value, in place. In a `Flow`, on the other hand, the input and output are not necessarily correlated in time. Outputs can "just happen", and inputs can be "swallowed", without a response.

In fact, you can create a `Flow` [from a separate `Source` and `Sink`](<https://doc.akka.io/api/akka/2.5.12/akka/stream/scaladsl/Flow$.html#fromSinkAndSource[I,O](sink:akka.stream.Graph[akka.stream.SinkShape[I],_],source:akka.stream.Graph[akka.stream.SourceShape[O],_]):akka.stream.scaladsl.Flow[I,O,akka.NotUsed]>) completely uncorrelated from each other. Or, you can create a `Flow` [from a function](<https://doc.akka.io/api/akka/2.5.12/akka/stream/scaladsl/Flow$.html#fromFunction[A,B](f:A=%3EB):akka.stream.scaladsl.Flow[A,B,akka.NotUsed]>) to have complete correlation. Or, you can use a whole bunch of features built into the `Flow` trait to achieve any combination. This flexibility is what makes Akka Streams so powerful, and we'll take advantage of this to build our server.

The last thing I want to say about `Flow`s is that you don't really call them like you call a function. Instead, you _materialize_ them in connection with a `Source` and `Sink` that provide their input and consume their output. The don't evaluate in-place in the program. They run until their `Source` completes or their `Sink` cancels. This fully connected system is called a `RunnableGraph`. In the case of Akka HTTP, the framework takes care of this materialization part for you, so all you have to do is design a `Flow`. It's really quite elegant!

### WebSockets in Akka HTTP

To serve WebSockets in Akka HTTP, you use a directive called `handleWebSocketMessages`. It accepts a `Flow[Message, Message, NotUsed]`, which encompasses the entirety of your WebSocket server logic. Pretty unconstrained, but that's because WebSocket is a pretty unconstrained protocol. From the description of `Flow` above, you might be able to see how it's a perfect abstraction.

`Message` is just a blob of text or binary data. Don't be fooled by the fact that it appears on both the input and output. The blobs in don't necessarily have to correlate with the blobs out. That's perfect because we can produce blobs out in response to events outside of the WebSocket system, and blobs in need not produce any blobs out.

All interpretation and processing of blobs is left to the developer and depends on how you build up your `Flow`. For that, we use the incredibly powerful Akka Streams API.

### Choosing the right Akka Streams abstraction

Akka Streams provides 3 ways I know of to build a our `Flow`. At the lowest level, you can make a [custom graph stage](https://doc.akka.io/docs/akka/2.5.14/stream/stream-customize.html). This seems pretty technical, and I've never tried it. The docs pretty much tell you that you probably don't need to.

Next, there are two separate DSLs. This is where I got confused, and I think a lot of people are in the same boat. The lower level DSL is the [Graph DSL](https://doc.akka.io/docs/akka/2.5.14/stream/stream-graphs.html). As its name suggests, it lets you design Akka Streams components with arbitrary numbers of inputs and outputs. It's really powerful, and most tutorials focus on using Akka Streams this way.

And then, there's another DSL. A simpler DSL. I don't know if the manual explicitly gives it a name, but [the API docs](https://doc.akka.io/api/akka/2.5.12/akka/stream/scaladsl/) call it the "flow DSL". It's almost completely free of boilerplate. The only catch is that it only directly supports the building of linear stream processes, without fancy features like feedback. But as it turns out, this is exactly the level of power we need to build the sort of `Flow` Akka HTTP requires for our WebSocket service.

With _all of that_ out of the way, we can finally get around to building something. Sorry it took so long, but take it from me, this is way quicker than reading the whole manual.

<a target="1"><sup>1</sup></a> Technically, it is `Function1[-T1, +R]`, which captures the variance constraints of the input and output parameters, but this isn't relevant here.
