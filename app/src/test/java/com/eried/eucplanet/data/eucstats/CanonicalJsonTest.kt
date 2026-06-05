package com.eried.eucplanet.data.eucstats

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalJsonTest {
    @Test fun matchesPythonVector_simpleObject() {
        val meta = JSONObject().put("b", 1).put("a", 2)
        // sha256 of '{"a":2,"b":1}' — computed from the server venv:
        // python -c "import hashlib,json;print(hashlib.sha256(json.dumps({'a':2,'b':1},sort_keys=True,separators=(',',':')).encode()).hexdigest())"
        assertEquals("d3626ac30a87e6f7a6428233b3c68299976865fa5508e4267c5415c76af7a772", CanonicalJson.requestHash(meta))
    }

    @Test fun stripsAttestationAndSortsNested() {
        val meta = JSONObject()
            .put("z", 1)
            .put("attestation", JSONObject().put("token", "x"))
            .put("wheel", JSONObject().put("model", "Master").put("brand", "Begode"))
        // attestation removed; nested keys sorted; equals the same object without attestation
        val expected = JSONObject().put("z", 1)
            .put("wheel", JSONObject().put("brand", "Begode").put("model", "Master"))
        assertEquals(CanonicalJson.requestHash(expected), CanonicalJson.requestHash(meta))
    }

    @Test fun matchesPythonVector_realisticEnvelope() {
        // Realistic FLOAT-FREE envelope: strings + ints + bools + nested wheel object
        // Python: json.dumps({'store_id':'s','tz_offset_min':120,'tz_known':True,'wheel':{'brand':'Begode','model':'Master'}},sort_keys=True,separators=(',',':'))
        // → '{"store_id":"s","tz_known":true,"tz_offset_min":120,"wheel":{"brand":"Begode","model":"Master"}}'
        // sha256 computed from the server venv:
        // python -c "import hashlib,json;print(hashlib.sha256(json.dumps({'store_id':'s','tz_offset_min':120,'tz_known':True,'wheel':{'brand':'Begode','model':'Master'}},sort_keys=True,separators=(',',':')).encode()).hexdigest())"
        val meta = JSONObject()
            .put("store_id", "s")
            .put("tz_offset_min", 120)
            .put("tz_known", true)
            .put("wheel", JSONObject().put("brand", "Begode").put("model", "Master"))
        assertEquals("2a745667ea39c1a82675d2bbb056385e6a5514267920de7c71c4c79c07c8e171", CanonicalJson.requestHash(meta))
    }
}
