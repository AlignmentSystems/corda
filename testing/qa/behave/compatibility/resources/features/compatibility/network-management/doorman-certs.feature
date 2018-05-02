@compatibility @doorman
Feature: Compatibility - Doorman certificate issuance
  To support an interoperable Corda network, a Corda node must have the ability to transact with another Corda node
  when each node has been issued a certificate by a different Doorman version.

  Scenario Outline: Corda (OS) nodes can transact with each other, where they have been issued Certificates by different (R3 Corda) Doorman versions.
    Given a node A of version <Corda-Node-Version>
    And node A was issued a certificate by <Doorman-Version-X>
    And node A has the finance app installed
    And a node B of version <Corda-Node-Version>
    And node B was issued a certificate by <Doorman-Version-Y>
    And node B has the finance app installed
    When the network is ready
    Then node A can transfer 100 tokens to node B

  Examples:
      | Corda-Node-Version    | Doorman-Version-X           | Doorman-Version-Y |
      | corda-3.0             | doorman-3.0.0-DEV-PREVIEW-3 | doorman-master    |
      | corda-3.1             | doorman-3.0.0-DEV-PREVIEW-3 | doorman-master    |

  Scenario Outline: R3 Corda nodes can transact with each other, where they have been issued Certificates by different (R3 Corda) Doorman versions.
    Given a node A of version <Corda-Node-Version>
    And node A was issued a certificate by <Doorman-Version-X>
    And node A has the finance app installed
    And a node B of version <Corda-Node-Version>
    And node B was issued a certificate by <Doorman-Version-Y>
    And node B has the finance app installed
    When the network is ready
    Then node A can transfer 100 tokens to node B

    Examples:
      | Corda-Node-Version           | Doorman-Version-X           | Doorman-Version-Y |
      | R3.CORDA-3.0.0-DEV-PREVIEW-3 | doorman-3.0.0-DEV-PREVIEW-3 | doorman-master    |

  Scenario Outline: Mixed (R3 and OS) Corda nodes can transact with each other, where they have been issued Certificates by different (R3 Corda) Doorman versions.
    Given a node A of version <Corda-Node-Version-X>
    And node A was issued a certificate by <Doorman-Version-X>
    And node A has the finance app installed
    And a node B of version <Corda-Node-Version-Y>
    And node B was issued a certificate by <Doorman-Version-Y>
    And node B has the finance app installed
    When the network is ready
    Then node A can transfer 100 tokens to node B

    Examples:
      | Corda-Node-Version-X         | Corda-Node-Version-Y  | Doorman-Version-X           | Doorman-Version-Y |
      | R3.CORDA-3.0.0-DEV-PREVIEW-3 | corda-3.0             | doorman-3.0.0-DEV-PREVIEW-3 | doorman-master    |
      | R3.CORDA-3.0.0-DEV-PREVIEW-3 | corda-3.1             | doorman-3.0.0-DEV-PREVIEW-3 | doorman-master    |

  Scenario Outline: Unhappy path scenarios to be added (eg. rejected issuance request). Please also see the OAT Test Suite (https://bitbucket.org/R3-CEV/corda-connect/qa/testing) which covers the CSR process from a functional perspective.
    Examples: TODO