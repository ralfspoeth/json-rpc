# json-rpc

A Java Servlet for handling JSON-RPC 2.0 requests.

## Overview

`json-rpc` provides a lightweight and easy-to-use Java Servlet designed to 
simplify the implementation of JSON-RPC 2.0 services within your web applications.
It leverages `io.github.ralfspoeth:json` for JSON processing and integrates 
seamlessly with standard Servlet containers.

The project consists of two modules:

*   `greyson-rpc` — the transport-independent JSON-RPC 2.0 engine
    (`GreysonRpcProcessor`) with a Greyson-native API. Depend on this
    directly if you want to work with Greyson's `JsonValue` types or
    plug the processor into a non-servlet transport.
*   `rpc-servlet` — servlet and websocket adapters with a completely
    Greyson-free API: implement `Procedure` in terms of plain
    `Map`/`List`/`Object` values; no Greyson types appear at compile time.

## Features

*   Handles JSON-RPC 2.0 requests.
*   Integrates with `jakarta.servlet-api`.
*   Uses `io.github.ralfspoeth:json` for efficient JSON parsing and generation.
*   Supports method invocation and error handling as per JSON-RPC 2.0 specification.

## Installation

To include `rpc-servlet` in your Maven project, add the following dependency 
to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.ralfspoeth</groupId>
    <artifactId>rpc-servlet</artifactId>
    <version>0.0.2</version> <!-- Or the latest release version -->
</dependency>
```

## Usage

(Further details on how to configure and use the servlet will be added here, 
including examples of how to define RPC methods and register them with the servlet.)

## Building from Source

This project uses Maven. To build it, navigate to the project root and run:

```bash
mvn clean install
```
## License

MIT. Copyright 2026 Ralf Spöth.
