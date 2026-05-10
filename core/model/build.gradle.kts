plugins {
    alias(libs.plugins.agentpost.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.szgenle.agentpost.core.model"
}

dependencies {
    // Room 注解：@Entity / @PrimaryKey / @ForeignKey / @Index
    // Entity 类直接落在本模块，遵循 HANDOVER 第 5 节的方案 B（model 与 Room Entity 合一）
    implementation(libs.androidx.room.runtime)

    // kotlinx.serialization：Attachment 通过 @Serializable 在 core:database 落成 JSON
    implementation(libs.kotlinx.serialization.json)
}
