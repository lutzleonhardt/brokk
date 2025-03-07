# Overview

Brokk (the [Norse god of the forge](https://en.wikipedia.org/wiki/Brokkr))
is the first code assistant that understands code semantically, not just
as chunks of text.  Brokk is designed to allow LLMs to work effectively
on large codebases that cannot be jammed entirely into working context.

# What Brokk can do

1. Ridiculously good agentic search / code retrieval. Better than Claude Code, better than Sourcegraph,
   better than Augment Code.  TODO link examples
1. Automatically determine the most-related classes to your working context and summarize them
1. Parse a stacktrace and add source for all the methods to your context
1. Add source for all the usages of a class, field, or method to your context
1. Pull in "anonymous" context pieces from external commands with `$$` or with `/paste`
1. Build/lint your project and ask the LLM to fix errors autonomously

These allow some simple but powerful patterns:
- "Here is the diff for commit X, which introduced a regression.  Here is the stacktrace
  of the error and the full source of the methods involved.  Find the bug."
- "Here are the usages of Foo.bar.  Is parameter zep always loaded from cache?"

# Brokk with o1pro

Brokk is particularly useful when making complex, multi-file edits with o1pro.

The `prepare` command will ask your normal editing model what extra context you might want to include
in your request.  Then use `copy` to pull all the content, including Brokk's prompts, into your clipboard.
Paste into o1pro and add your request at the bottom.  Then paste o1pro's response back into
Brokk and it will apply the edits.

# Current status

We are currently focused on making Brokk's Java support the best in the world.
Other languages will follow.

### Lombok fine print!

Joern (the code intelligence engine) needs to run delombok before it can analyze anything.
Delombok is unusably slow for anything but trivial projects, making Brokk a poor fit for
Lombok-using codebases.

### Known issues

"Stop" button does not work during search.  This is caused by
https://github.com/langchain4j/langchain4j/issues/2658

# Getting started

Requirements: Java 21+

1. `cd /path/to/my/project`
2. `export ANTHROPIC_API_KEY=xxy`
   - or, `export OPENAI_API_KEY=xxy`
   - or, `export DEEPSEEK_API_KEY=xxy`
   - other providers and models are technically supported, making them easier to use is high priority.
     In the meantime, look at Models.java for how to set up a ~/.config/brokk/brokk.yml file with
     your preferred option if the defaults don't work for you.
1. `java -jar /path/to/brokk/brokk-0.1.jar`

There is a [Brokk Discord](https://discord.gg/ugXqhRem) for questions and suggestions.

# Finer points on some commands

- Brokk doesn't offer automatic running of tests (too much variance in what you might want it to do).
  Instead, Brokk allows you to run arbitrary shell commands, and import those as context with "Capture Text"
  or "Edit Files."

# FAQ

1. What code intelligence library does Brokk use?

Brokk uses [Joern](https://github.com/joernio/joern), an industrial-strength code analysis engine from [Qwiet](qwiet.ai) (formerly Shiftleft).  I spent multiple days evaluating all the relevant options and Joern is the only one powerful enough (and fast enough) to do what I want, so huge thanks to the team at Qwiet for that!
