# Fogg v0.8.1

Release de correcciones sobre v0.8, con dos funciones nuevas. Todo lo que sigue
está verificado contra un Kronos Thunder real (`JL` / `AM05`,
`Kronos_Thunder_V006`), no solo compilado.

## Correcciones

**El control de música ya responde al play.** Adelantar y atrasar pista
funcionaban, y eso escondía el fallo: `WatchMusicController.onWatchCommand()`
tenía los siete comandos mapeados y nadie lo llamaba nunca, porque el
despachador BLE no tenía rama para `cmd 0x04 / key 0x02`. Cada pulsación caía en
un `Unhandled response`. Las pistas cambiaban porque el reloj también es un
dispositivo AVRCP por Bluetooth clásico y ese camino funciona por su cuenta;
play/pausa no tenía nada detrás.

**La presión arterial y el SpO2 ya no parecen congelados.** Siempre se
sincronizaron —el reloj los envía y el parser los guarda—, pero las tarjetas
pasaban `null` como hora y como gráfica, y `latestBp()` descartaba la marca de
tiempo. Ahora llevan hora y tendencia como el resto de series.

**El ciclo menstrual rechaza fechas futuras.** Una fecha de inicio posterior a
hoy hacía que app y reloj mostrasen números distintos y ambos falsos: el móvil
envolvía `-3` a día 26 y el reloj, que trata la diferencia como entero sin signo,
a día 9. Los tres puntos de entrada (ajustes, registrar fecha y la cuadrícula del
calendario) podían ofrecerla. Además la pantalla muestra ahora los dos hitos
—ovulación y apertura de la ventana fértil—, que es el número que cita el reloj.

**La bolsa ya no se autoborra en falso.** El ACK sin cuerpo de nuestro propio
"borrar todo" se leía como si el reloj anunciara un borrado; escribía
`Watch deleted STOCK id=-1` en cada refresco y habría borrado una fila real el
día que un id coincidiera.

## Novedades

**Buscador de valores con cotizaciones reales.** El alta ofrecía ocho empresas
incrustadas en el código con precios fijos, y una opción "personalizado" que te
pedía teclear el precio a mano. Ahora buscas cualquier ticker y se añade con su
cotización en vivo, más un botón para recotizar todo y reenviarlo al reloj.
Usa endpoints públicos sin clave, con respaldo en un segundo proveedor si el
primero limita por tasa; un símbolo que falle se deja intacto en vez de ponerse
a cero.

**Avisos donde el dato no está medido.** Dos métricas de este reloj no salen de
un sensor, y una cifra con gráfica debajo invita a creer lo contrario:

- *Oxigenación*: en relojes de esta gama suele estar sintetizada. La tarjeta
  propone una prueba que puedes hacer tú —medir sobre un objeto inanimado— y
  explica qué significa que siga marcando 98 %.
- *Presión arterial*: se deriva del pulso con una ecuación fija. Medirla de
  verdad exige un brazalete que se infle en la muñeca.

Ambas cierran por el uso que causa daño real: no tomar decisiones de salud con
esas cifras.

## Funciones retiradas

Cinco filas prometían comportamiento que este reloj no tiene. Quedan comentadas,
no borradas, y los emisores siguen en `BleManager` porque las codificaciones son
correctas para un reloj que sí lleve el hardware:

| Fila | Motivo |
|---|---|
| Lavado de manos (`0x0226`) | El registro existe, la función no |
| Temperatura automática (`0x021B`) | Igual |
| SOS (`0x024E`) | El reloj guarda el número y no tiene SOS que lo dispare |
| Buscar el reloj (`0x0234`) | La trama se ACKea y el reloj nunca suena |
| Girar muñeca para foto | No existe como clave del protocolo |

Esto corrigió el criterio del propio barrido de capacidades: **que una clave
responda a un READ prueba que existe el registro, no que exista la función**. El
firmware es común a toda una familia de productos.

## Documentación

Cuatro documentos nuevos de protocolo y dos correcciones:

- `16-STANDBY-AND-AOD.md` — las dos claves del AOD y cómo distinguir una clave
  no implementada de un ACK.
- `17-WORLD-CLOCK-AND-STOCK.md` — lectura paginada del reloj mundial, formato de
  bolsa y de dónde salen las cotizaciones.
- `18-GIRL-CARE.md` — parámetros del ciclo, el desbordamiento sin signo y por qué
  reloj y app cuentan cosas distintas.
- `19-FIRMWARE-CAPABILITY-SURVEY.md` — barrido clave por clave de lo que este
  firmware implementa, los huecos que quedan y lo que no vale la pena construir.
- `15-MONITORING-AND-ACTIONS.md` — corregido: la verificación de "buscar el
  reloj" se apoyaba en un ACK que no prueba nada.
