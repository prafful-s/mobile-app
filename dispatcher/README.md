# Dispatcher configuration

Flexible-mode layout for the AEM as a Cloud Service Dispatcher SDK.

`src/conf.dispatcher.d/filters/contentbridge_filters.any` is the file that matters most: it is the
only place where `/bin/contentbridge/pages` is opened to the outside world. The SDK's default
deny-all remains in force for everything else.

Validate locally before committing:

```bash
$AEM_SDK/dispatcher/bin/validator full -d out ./src
$AEM_SDK/dispatcher/bin/docker_run.sh out host.docker.internal:5403 8080
```
