# Introduction

This project is the combination of a series of a demo application and a series of blog posts. I've put it together to scratch several itches, and hopefully the broader world will find it useful. This first post will outline these broader goals. But first, I think it may help to explain where I'm coming from...

## Background

At the time of writing, I've been programmming Scala for about 5 years. I first started at HuffPost, where Scala was used pretty extensively for a while. And then I used it at Artsy at the core of their systems for bidding in live auctions.

Like many people, my introduction to Scala came through the excellent [Functional Programming Principles in Scala](https://www.coursera.org/learn/progfun1) course on Coursera (AKA The Odersky Course) and the equally excellent and similarly named [Functional Programming in Scala](https://www.manning.com/books/functional-programming-in-scala) book (AKA The Red Book). Learning the language was a mind bender at times. But those resources guided me through the learning curve. I found the payoff of finding elegant solutions addictive. So was the experience "if it compiles, it's probably both correct and resillient". I pretty quickly decided that, going forward, I wanted to write software _like this_.

However, in both major projects I launched in Scala, I ran into another learning curve. There is very little guidance in how to design _systems_ in Scala. Actually, that's wrong. There is _a lot_ of guidance out there, but simply no consensus.

## Objectives

1. Demonstrate a full-stack Scala application that is simple enough to completely understand quickly, but complex enough to motiviate the use of some system design techniques.

2. Explore some small-scale techniques that can help out a great deal in writing concise, expressive logic.

3. Demonstrate a real-time system based around web sockets, using Akka HTTP.

To meet these objectives, this project implements an IRC-like chat service. This type of application is often used in Web Socket demos because a good chat experience requires server-push.
