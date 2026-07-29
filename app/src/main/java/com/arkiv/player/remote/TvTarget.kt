package com.arkiv.player.remote

/**
 * ¿Hay una TV a la que este teléfono pueda enviar contenido?
 *
 * El descubrimiento LAN es anónimo: el responder UDP lo arranca CUALQUIER instalación de Arkiv
 * (también otro teléfono), así que por sí solo no prueba que exista una relación con esa TV. Sin
 * este gate, tener Arkiv en un Fire Stick de la misma WiFi bastaba para que saliera el diálogo
 * "¿Qué quieres hacer?" y el icono del control remoto sin haber pareado nunca.
 *
 * Por eso el pareo manda: [linked] es el flag persistido ("pareé una TV alguna vez"), y sobre él
 * la LAN o la nube solo dicen POR DÓNDE llegarle. Se mantiene [lanFound] como alternativa a
 * [pairedInAccount] para que un usuario pareado siga pudiendo enviar por LAN aunque esté sin
 * internet (resolver el device kind=tv en PocketBase necesita red).
 */
fun tvTargetAvailable(linked: Boolean, lanFound: Boolean, pairedInAccount: Boolean): Boolean =
    linked && (lanFound || pairedInAccount)
