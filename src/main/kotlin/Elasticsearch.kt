package com.example

import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class ElasticsearchClient(private val baseUrl: String) {
    private val http = HttpClient.newHttpClient()
    private val index = "cities"

    fun indexCity(city: CityView) {
        request("PUT", "/$index/_doc/${city.id}", buildJsonObject {
            put("id", city.id)
            put("name", city.name)
            put("population", city.population)
        }.toString())
    }

    fun reindex(cities: List<CityView>) {
        request(
            "PUT",
            "/$index",
            """{"mappings":{"properties":{"id":{"type":"integer"},"name":{"type":"text"},"population":{"type":"integer"}}}}""",
            allowStatus = setOf(400),
        )
        cities.forEach(::indexCity)
    }

    fun searchByName(name: String): List<CityView> {
        val response = request("POST", "/$index/_search", buildJsonObject {
            put("size", 100)
            putJsonObject("query") {
                putJsonObject("match") { put("name", name) }
            }
        }.toString())
        val hits = Json.parseToJsonElement(response).jsonObject["hits"]?.jsonObject
            ?.get("hits")?.jsonArray ?: return emptyList()
        return hits.mapNotNull { hit ->
            val source = hit.jsonObject["_source"]?.jsonObject ?: return@mapNotNull null
            CityView(source["id"]!!.jsonPrimitive.int, source["name"]!!.jsonPrimitive.content, source["population"]!!.jsonPrimitive.int)
        }
    }

    private fun request(method: String, path: String, body: String, allowStatus: Set<Int> = emptySet()): String {
        val request = HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299 && response.statusCode() !in allowStatus) {
            error("Elasticsearch request failed (${response.statusCode()}): ${response.body()}")
        }
        return response.body()
    }
}