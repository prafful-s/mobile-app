# Sample AEM project template

This is a project template for AEM-based applications. It is intended as a best-practice set of examples as well as a potential starting point to develop your own functionality.

## Modules

The main parts of the template are:

* [core:](core/README.md) Java bundle containing all core functionality like OSGi services, listeners or schedulers, as well as component-related Java code such as servlets or request filters.
* [it.tests:](it.tests/README.md) Java based integration tests
* [ui.apps:](ui.apps/README.md) contains the /apps (and /etc) parts of the project, ie JS&CSS clientlibs, components, and templates
* [ui.content:](ui.content/README.md) contains sample content using the components from the ui.apps
* ui.config: contains runmode specific OSGi configs for the project
* [ui.frontend:](ui.frontend.general/README.md) an optional dedicated front-end build mechanism (Angular, React or general Webpack project)
* [ui.tests:](ui.tests/README.md) Cypress based UI tests (for other frameworks check [aem-test-samples](https://github.com/adobe/aem-test-samples) repository
* all: a single content package that embeds all of the compiled modules (bundles and content packages) including any vendor dependencies
* analyse: this module runs analysis on the project which provides additional validation for deploying into AEMaaCS

## Review-order confirmation button

The confirmation button's shared content and styles are configured in
`core/src/main/resources/edsMobileApp/components/confirm-button.json`. Java generates
only its component metadata (ID, order, and component type).

The authored `order-summary` component's JCR property `mobilebuttoneventtype`
is mapped to `eventType` by `mappings/orderSummary.json`. Review-order assembly
passes this mapped value to Confirm, without a second JCR lookup, and removes
the intermediate field from the exported `cartSummary` content. A nonblank value
is emitted as both Confirm's `content.events` and `content.customEvent`. When absent or
blank, both event fields are omitted; no default event is fired by this configuration.

To override other button content for one site, add this section to that site's
`core/src/main/resources/edsMobileApp/sites/<site>/defaults.json`:

```json
{
    "confirmButton": {
        "content": {
            "label": "Place order"
        }
    }
}
```

JSON overrides for `events` and `customEvent` are ignored. Other `content` fields, such as
`label`, `formId`, `linkTo`, and `message`, and individual `styles` fields can also
be overridden. Omitted fields keep the shared defaults; an empty string clears
a configured string value. Configuration ships with the bundle and requires a
rebuild and deployment to change.

## Theme Form Field Overrides

Configure exported form field paths in
`core/src/main/resources/edsMobileApp/forms/field-path-overrides.json`:

```json
{
    "carvelo-theme": {
        "person.mobileNumber": "mobilePhone.number"
    }
}
```

The outer key matches the app root's authored `theme` property (for example,
`carvelo-theme`), inherited from the nearest parent within the site when absent.
If no page theme is authored, the resolved theme Content Fragment name is used
as a fallback. It is not the site folder or theme model. Overrides run after
all screens have been assembled. Add any original field path and replacement
under that theme to override it across all exported forms, including authored
fields, bundled schemas, nested fields, and checkbox options. Only exact `path`
matches inside form content are replaced, once per field. Other themes,
unmatched paths, field IDs, labels, and source schemas remain unchanged.
Blank replacements are ignored. Changes require a bundle rebuild and deployment.

## Authorable Form Headings

All supported form models in `migration-parent-repo` expose **Mobile Form Heading**
(`mobileFormHeading`) under the **Mobile Specific** tab. Models without tabs now
have **Basic** and **Mobile Specific** tabs: web/general properties stay in Basic,
and mobile-only properties sit in Mobile Specific. This includes all bundled
forms, flight search, and the authored Adaptive Form model.

In the v2 export, a nonblank `mobileFormHeading` becomes a standalone `title`
component immediately before the form, with medium size, bold weight, and left
alignment. The lowercase alias `mobileformheading` is also accepted. This works
even for forms without a compiled heading default and authored forms without a
schema name. The heading is not an input field or form metadata.

Blank or absent values retain the existing `formHeadings` site default, then the
compiled default in `FormBlockBuilder`. For example, credit-card application keeps
"Credit Card Application", loan application keeps "Home Loan Application Form",
and plan application keeps "Plan Application". Forms without a previous default
(including test drive, schedule appointment/consultation, automatic payments,
additional services, and the coffee quiz) still omit the pre-form heading unless
one is authored or configured.

The mobile exporter no longer reads `form-title`, `formtitle`,
`createaccounttitle`, `title`, `formHeading`, or `formheading` as heading sources.
To retain a customized web heading in the mobile output, copy its text into
`mobileFormHeading`. The original fields remain available for web rendering and
analytics; neither web JavaScript nor form fields/submission behavior changes.
Existing `forms.<screenKey>.heading` site overrides still run after heading
generation and can replace the title; remove that override when the authored
mobile heading should control the text.

Run `npm run build:json:models` in `migration-parent-repo` after changing the
authoring models to regenerate the Universal Editor's `component-models.json`.
Rebuild and deploy the core bundle, then re-export v2 JSON to see exporter changes.

## Checkout Submit Event

Checkout's `meta.eventType` comes only from the `checkout-shipping` component's
authored `mobilebuttoneventtype` property. An absent or blank property leaves the
event blank. `checkoutForm.eventType` site overrides are ignored, and no
`commerce.checkouts` fallback is applied. Other checkout defaults are unchanged.

## HTMLText styles

The text-to-`htmlText` mapping exports `styles.padding` and `styles.color` only
when authored and nonblank. Invalid hex colors are omitted. If no styles remain,
the component has no `styles` object. Valid `custommobilestyle` rows still add or
override styles. The content skeleton is unchanged: missing text remains `""`.

## How to build

To build all the modules run in the project root directory the following command with Maven 3:

    mvn clean install

To build all the modules and deploy the `all` package to a local instance of AEM, run in the project root directory the following command:

    mvn clean install -PautoInstallSinglePackage

Or to deploy it to a publish instance, run

    mvn clean install -PautoInstallSinglePackagePublish

Or alternatively

    mvn clean install -PautoInstallSinglePackage -Daem.port=4503

Or to deploy only the bundle to the author, run

    mvn clean install -PautoInstallBundle

Or to deploy only a single content package, run in the sub-module directory (i.e `ui.apps`)

    mvn clean install -PautoInstallPackage

## Documentation

The build process also generates documentation in the form of README.md files in each module directory for easy reference. Depending on the options you select at build time, the content may be customized to your project.

## Testing

There are three levels of testing contained in the project:

### Unit tests

This show-cases classic unit testing of the code contained in the bundle. To
test, execute:

    mvn clean test

### Integration tests

This allows running integration tests that exercise the capabilities of AEM via
HTTP calls to its API. To run the integration tests, run:

    mvn clean verify -Plocal

Test classes must be saved in the `src/main/java` directory (or any of its
subdirectories), and must be contained in files matching the pattern `*IT.java`.

The configuration provides sensible defaults for a typical local installation of
AEM. If you want to point the integration tests to different AEM author and
publish instances, you can use the following system properties via Maven's `-D`
flag.

| Property              | Description                                         | Default value           |
|-----------------------|-----------------------------------------------------|-------------------------|
| `it.author.url`       | URL of the author instance                          | `http://localhost:4502` |
| `it.author.user`      | Admin user for the author instance                  | `admin`                 |
| `it.author.password`  | Password of the admin user for the author instance  | `admin`                 |
| `it.publish.url`      | URL of the publish instance                         | `http://localhost:4503` |
| `it.publish.user`     | Admin user for the publish instance                 | `admin`                 |
| `it.publish.password` | Password of the admin user for the publish instance | `admin`                 |

The integration tests in this archetype use the [AEM Testing
Clients](https://github.com/adobe/aem-testing-clients) and showcase some
recommended [best
practices](https://github.com/adobe/aem-testing-clients/wiki/Best-practices) to
be put in use when writing integration tests for AEM.

## Static Analysis

The `analyse` module performs static analysis on the project for deploying into AEMaaCS. It is automatically
run when executing

    mvn clean install

from the project root directory. Additional information about this analysis and how to further configure it
can be found here https://github.com/adobe/aemanalyser-maven-plugin

### UI tests

They will test the UI layer of your AEM application using Cypress framework.

Check README file in `ui.tests` module for more details.

Examples of UI tests in different frameworks can be found here: https://github.com/adobe/aem-test-samples

## ClientLibs

The frontend module is made available using an [AEM ClientLib](https://helpx.adobe.com/experience-manager/6-5/sites/developing/using/clientlibs.html). When executing the NPM build script, the app is built and the [`aem-clientlib-generator`](https://github.com/wcm-io-frontend/aem-clientlib-generator) package takes the resulting build output and transforms it into such a ClientLib.

A ClientLib will consist of the following files and directories:

- `css/`: CSS files which can be requested in the HTML
- `css.txt` (tells AEM the order and names of files in `css/` so they can be merged)
- `js/`: JavaScript files which can be requested in the HTML
- `js.txt` (tells AEM the order and names of files in `js/` so they can be merged
- `resources/`: Source maps, non-entrypoint code chunks (resulting from code splitting), static assets (e.g. icons), etc.

## Maven settings

The project comes with the auto-public repository configured. To setup the repository in your Maven settings, refer to:

    http://helpx.adobe.com/experience-manager/kb/SetUpTheAdobeMavenRepository.html
