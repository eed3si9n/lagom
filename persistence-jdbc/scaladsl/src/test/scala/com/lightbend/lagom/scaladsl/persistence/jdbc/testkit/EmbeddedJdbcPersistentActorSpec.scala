/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.scaladsl.persistence.jdbc.testkit

import com.lightbend.lagom.scaladsl.persistence.TestEntitySerializerRegistry
import com.lightbend.lagom.scaladsl.persistence.jdbc.JdbcPersistenceSpec
import com.lightbend.lagom.scaladsl.persistence.testkit.AbstractEmbeddedPersistentActorSpec

class EmbeddedJdbcPersistentActorSpec
    extends JdbcPersistenceSpec(AbstractEmbeddedPersistentActorSpec.EmbeddedPersistentActorSerializers)
    with AbstractEmbeddedPersistentActorSpec
