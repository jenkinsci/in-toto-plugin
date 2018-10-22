in-toto Jenkins
===============

This repository contains the source code for the in-toto provenance plugin for
Jenkins.

As of now, this is still WIP, although the core features are implemented.

## Installation

ou can compile the plugin by running `mvn package`, and then
manually install the `.hpi` file in your Jenkins installation.

A Makefile is provided to build the plugin. `make build` will simply run `mvn
package` (needs JDK 8), whereas `make docker-build` will do the same inside a
container, meaning you don't have to worry about having the JDK installed.

We intend to move this to the plugin site for Jenkins once a more mature
implementation is reached.

## Usage in free-style projects

This plugin exposes a "post build" entry in the task menu. When selecting it
you will be prompted to fill the following information in:

- stepName: the name of the step for this Jenkins pipeline (more on that later)
- credentialId: Id of File Credential as the signing key used to sign the link metada. *
- keyPath: the path to the signing key used to sign the link metadata. *
- transport: a URI to where to post the metadata upon
  completion.

* You should either fill the credentialId or keyPath to assgin a key for signing the link metadata.

Once this is done, the plugin will take care of generating and tracking
information of the build process.

## Usage in declarative pipelines

You can also use it in declarative pipelines by using the `in_toto_wrap`
syntax. It takes the same arguments as before. For example, to wrap a simple
shellscript step you would add the following to your Jenkinisfile:

```

pipeline {
  agent none

  stages {
    stage('Build') {
      agent { label 'worker 1' }

      steps {
        in_toto_wrap(['stepName': 'Build',
            'credentialId': 'keyId01',
            'transport': 'redis://redis']){
          echo 'Building..'
        }
      }
    }
  }
}
```

This will produce a piece of link metadata and post it to a redis server.
Currently, we have transport handlers for redis, etcd and an unauthenticated
POST request with the link metadata.

If using the keypath parameter, the path must be located on the remote worker. The plugin uses a
`MasterToSlave` handler to serialize in-toto code to capture the in-toto
metadata natively in any worker. This both serves to not expose the slave's key
unecessarilly in the Master's filesystem and to authenticate any worker that
performed the pipeline step.

### Using transports:

The transport is chosen by specifying the protocol header on the uri. For
example, for a redis transport you'd use `redis://` and for an etcd transport
you'd specify `etcd://`. `https` and `http` are handled using a generic HTTP
POST request with the link metadata as the body of the request. 

You can easily extend transports by extending the abstract class `Transport` in
the `io.jenkins.plugins.intotorecorder.transport` package. Discovery of new
handlers is not automatic (yet!) though.

## Limitations

As of now, the current limitations exist:

- There hasn't been much thorough testing with the pipeline plugin. Although it
  *should* work, there may be some rough edges to fix up.
- If using the credentialId, the metadata will be signed in master.
- There should be other interesting settings to add (e.g., ignore patterns,
  etc.). Right now, and due to the way the workspaces are created in Jenknis,
  the whole of the .git folder is tracked upon execution (which increases the
  metadata size significantly). A mechanism like .in-totoignore files in the
  python implementation would be useful
