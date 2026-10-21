# El descriptor HID y los reportes son arrays de bytes, no hay reflexion:
# las reglas por defecto de AGP alcanzan para casi todo.

# OkHttp trae sus propias reglas, pero referencia clases opcionales de
# Conscrypt/BouncyCastle que no usamos; silenciamos los avisos.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
