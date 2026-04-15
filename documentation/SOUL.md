# Overview of the "Messenger Community Intelligence Platform" (ECIP)
This "Messenger Community Intelligence Platform" (ECIP) is an enterprise-grade, microservice-based platform built on Java 25 and Spring Boot 4 that analyzes Telegram groups, channels, and discussion threads in real time, detects communication contexts, and reacts based on rules. 

Unlike traditional bots, the system is TDLib-first, meaning it operates as a full Telegram client. The Bot API remains an optional additional channel. 

## Operating Modes
The platform acts as a context-sensitive communication assistant with four operating modes: 
- React (on direct mention), Summarize (when threads become confusing), 
- Moderate (on rule violations), 
- Observe (when no intervention is required).

## Vision 
Vision is to create a 
- secure
- auditable
- scalable 
platform that understands Telegram communication, supports contextually, and acts in a controlled manner without handing over control to AI. 

## Mission
The mission is to 
- automatically analyze communities, 
- provide targeted support, 
- moderate them—with traceable decisions and strict policy logic. 

## Success Criteria
Success criteria include 
- relevant responses instead of spam, 
- high domain precision with low model costs, 
- clean auditability and observability, 
- extendability to additional messenger/community platforms. 

## Technical Guidelines
The technical guidelines specify the 
- base stack (Java 25, Spring Boot 4, Maven, TDLib) 
- architectural principles (event-driven first, clear separation of concerns, TDLib-first, policy and moderation logic for every response, full auditability and observability). 

## Non-Functional Requirements
Non-functional requirements include 
- high traceability, 
- multi-tenancy, 
- low latency & retry strategies, 
- cost control for LLM calls, 
- secure token/secret management, 
- complete logs, metrics, and traces. 