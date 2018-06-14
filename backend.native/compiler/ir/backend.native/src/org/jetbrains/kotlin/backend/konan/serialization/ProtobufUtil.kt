/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.serialization.deserialization.descriptors.*
import org.jetbrains.kotlin.metadata.KonanIr
import org.jetbrains.kotlin.metadata.konan.KonanProtoBuf
import org.jetbrains.kotlin.metadata.ProtoBuf

fun newUniqId(uniqId: UniqId): KonanIr.UniqId =
   KonanIr.UniqId.newBuilder()
       .setIndex(uniqId.index)
       .setIsLocal(uniqId.isLocal)
       .build()

fun newDescriptorUniqId(index: Long): KonanProtoBuf.DescriptorUniqId =
    KonanProtoBuf.DescriptorUniqId.newBuilder().setIndex(index).build()

fun KonanIr.UniqId.uniqId(): UniqId = UniqId(this.index, this.isLocal)
fun KonanIr.UniqId.uniqIdKey(moduleDescriptor: ModuleDescriptor) =
    UniqIdKey(moduleDescriptor, this.uniqId())

fun DeclarationDescriptor.getUniqId(): KonanProtoBuf.DescriptorUniqId? = when (this) {
    is DeserializedClassDescriptor -> if (this.classProto.hasExtension(KonanProtoBuf.classUniqId)) this.classProto.getExtension(KonanProtoBuf.classUniqId) else null
    is DeserializedSimpleFunctionDescriptor -> if (this.proto.hasExtension(KonanProtoBuf.functionUniqId)) this.proto.getExtension(KonanProtoBuf.functionUniqId) else null
    is DeserializedPropertyDescriptor -> if (this.proto.hasExtension(KonanProtoBuf.propertyUniqId)) this.proto.getExtension(KonanProtoBuf.propertyUniqId) else null
    is DeserializedClassConstructorDescriptor -> if (this.proto.hasExtension(KonanProtoBuf.constructorUniqId)) this.proto.getExtension(KonanProtoBuf.constructorUniqId) else null
    else -> null
}
