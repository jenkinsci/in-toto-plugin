in-toto Jenkins
===============

This repository contains the source code for the in-toto provenance plugin for
Jenkins.

As of now, this is still WIP, although the core features are implemented.

## Installation

As of now, you can compile the plugin by running `mvn package` (note, make sure
you have the [in-toto-java jar available](https://github.com/controlplaneio/in-toto-java).
and manually install the `.hpi` file in your Jenkins installation.

## Usage

This plugin exposes a "post build" entry in the task menu. When selecting it
you will be prompted to fill the following information in:

- step name: the name of the step for this Jenkins pipeline (more on that later)
- key path: the path to the signing key used to sign the link metadata.
- transport: (**NOT YET IMPLEMENTED**) a URI to where to post the metadata upon
  completion.

Once this is done, the plugin will take care of generating and tracking
information of the build process.

## Limitations

As of now, the current limitations exist:

- As of now, the provenance module treats all the pipeline as a single step.
- There hasn't been much thorough testing with the pipeline plugin. Although it
  *should* work, there may be some rough edges to fix up.
- There should be other interesting settings to add (e.g., ignore patterns,
  etc.)
- The FilePath object can be used to execute code on remote workers (so as to
  simplify tracking), but it isn't being used now.

Hopefully these are removed soon!
