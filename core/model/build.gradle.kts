plugins {
    alias(libs.plugins.agentpost.android.library)
}

android {
    namespace = "com.szgenle.agentpost.core.model"
}

dependencies {
    // Room 注解：@Entity / @PrimaryKey / @ForeignKey / @Index
    // Entity 类直接落在本模块，遵循 HANDOVER 第 5 节的方案 B（model 与 Room Entity 合一）
    implementation(libs.androidx.room.runtime)
}
