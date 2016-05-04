package com.boundary.ordasity.metrics

import com.codahale.metrics.SharedMetricRegistries
import nl.grons.metrics.scala.InstrumentedBuilder

object OrdasityMetricRegistry {
    lazy val registry = SharedMetricRegistries.getOrCreate("ordasity")
}

trait Instrumented extends InstrumentedBuilder {
  val metricRegistry = OrdasityMetricRegistry.registry
}
