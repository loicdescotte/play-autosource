/**
  * Copyright 2013 Mathieu ANCELIN (@TrevorReznik)
  *
  * Licensed under the Apache License, Version 2.0 (the "License");
  * you may not use this file except in compliance with the License.
  * You may obtain a copy of the License at
  *
  *     http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package play.autosource.couchbase

import play.api.libs.json._
import play.api.libs.json.syntax._
import play.api.libs.json.extensions._
import play.autosource.core.{AutoSourceRouterContoller, AutoSource}
import scala.concurrent.{Future, ExecutionContext}
import play.api.libs.iteratee.{Iteratee, Enumerator}

import org.ancelin.play2.couchbase.{CouchbaseRWImplicits, Couchbase, CouchbaseBucket}
import java.util.UUID
import com.couchbase.client.protocol.views.{Query, View}
import play.api.mvc._
import org.ancelin.play2.couchbase.crud.QueryObject

class CouchbaseAutoSource[T:Format](bucket: CouchbaseBucket) extends AutoSource[T, String, (View, Query), T] {

  import org.ancelin.play2.couchbase.CouchbaseImplicitConversion.Couchbase2ClientWrapper
  import org.ancelin.play2.couchbase.CouchbaseRWImplicits._

  val reader: Reads[T] = implicitly[Reads[T]]
  val writer: Writes[T] = implicitly[Writes[T]]

  def insert(t: T)(implicit ctx: ExecutionContext): Future[String] = {
    val id = UUID.randomUUID().toString
    var json = writer.writes(t).as[JsObject]
    if ((json \ "_id").asOpt[String].isEmpty) {
      json = json ++ Json.obj("_id" -> id)
    }
    bucket.set(id, json)(bucket, CouchbaseRWImplicits.jsObjectToDocumentWriter, ctx).map(_ => id)(ctx)
  }

  def get(id: String)(implicit ctx: ExecutionContext): Future[Option[(T, String)]] = {
    bucket.get[T]( id )(bucket ,reader, ctx).map( _.map( v => ( v, id ) ) )(ctx)
  }

  def delete(id: String)(implicit ctx: ExecutionContext): Future[Unit] = {
    bucket.delete(id)(bucket, ctx).map(_ => ())
  }

  def update(id: String, t: T)(implicit ctx: ExecutionContext): Future[Unit] = {
    bucket.replace(id, t)(bucket, writer, ctx).map(_ => ())
  }

  def updatePartial(id: String, upd: T)(implicit ctx: ExecutionContext): Future[Unit] = {
    update(id, upd)(ctx)
  }

  def batchInsert(elems: Enumerator[T])(implicit ctx: ExecutionContext): Future[Int] = {
    elems(Iteratee.foldM[T, Int](0)( (s, t) => insert(t)(ctx).map(_ => s + 1))).flatMap(_.run)
  }

  def find(sel: (View, Query), limit: Int = 0, skip: Int = 0)(implicit ctx: ExecutionContext): Future[Seq[(T, String)]] = {
    var query = sel._2
    if (limit != 0) query = query.setLimit(limit)
    if (skip != 0) query = query.setSkip(skip)
    bucket.find[T](sel._1)(query)(bucket, reader, ctx).map(l => l.map(i => (i, (Json.toJson(i)(writer) \ "id").as[String])))
  }

  def findStream(sel: (View, Query), skip: Int = 0, pageSize: Int = 0)(implicit ctx: ExecutionContext): Enumerator[Iterator[(T, String)]] = {
    var query = sel._2
    if (skip != 0) query = query.setSkip(skip)
    val futureEnumerator = bucket.find[T](sel._1)(query)(bucket, reader, ctx).map { l =>
      val size = if(pageSize != 0) pageSize else l.size
      Enumerator.enumerate(l.map(i => (i, (Json.toJson(i)(writer) \ "_id").as[String])).grouped(size).map(_.iterator))
    }
    Enumerator.flatten(futureEnumerator)
  }

  def batchDelete(sel: (View, Query))(implicit ctx: ExecutionContext): Future[Unit] = {
    bucket.find[JsObject](sel._1)(sel._2)(bucket, CouchbaseRWImplicits.documentAsJsObjectReader, ctx).map { list =>
      list.map { t =>
        delete((t \ "_id").as[String])(ctx)
      }
    }
  }

  def batchUpdate(sel: (View, Query), upd: T)(implicit ctx: ExecutionContext): Future[Unit] = {
    bucket.find[T](sel._1)(sel._2)(bucket, reader, ctx).map { list =>
      list.map { t =>
        val json = Json.toJson(t)(writer)
        update((json \ "_id").as[String], t)(ctx)
      }
    }
  }

  def view(docName: String, viewName: String)(implicit ctx: ExecutionContext): Future[View] = {
    bucket.view(docName, viewName)(bucket, ctx)
  }
}

abstract class CouchbaseAutoSourceController[T:Format](implicit ctx: ExecutionContext) extends AutoSourceRouterContoller[String] {

  import org.ancelin.play2.couchbase.CouchbaseImplicitConversion.Couchbase2ClientWrapper

  val bucket: CouchbaseBucket
  lazy val res = new CouchbaseAutoSource[T](bucket)
  val defaultDesignDocname = ""
  val defaultViewName= ""

  val writerWithId = Writes[(T, String)] {
    case (t, id) =>
      Json.obj("id" -> id) ++
        res.writer.writes(t).as[JsObject]
  }

  def insert: EssentialAction = Action(parse.json) { request =>
    Json.fromJson[T](request.body)(res.reader).map{ t =>
      Async{
        res.insert(t).map{ id => Ok(Json.obj("id" -> id)) }
      }
    }.recoverTotal{ e => BadRequest(JsError.toFlatJson(e)) }
  }

  def get(id: String): EssentialAction = Action {
    Async{
      res.get(id).map{
        case None    => NotFound(s"ID '${id}' not found")
        case Some(tid) => Ok(Json.toJson(tid._1)(res.writer))
      }
    }
  }

  def delete(id: String): EssentialAction = Action {
    Async{
      res.delete(id).map{ le => Ok(Json.obj("id" -> id)) }
    }
  }

  def update(id: String): EssentialAction = Action(parse.json) { request =>
    Json.fromJson[T](request.body)(res.reader).map{ t =>
      Async{
        res.update(id, t).map{ _ => Ok(Json.obj("id" -> id)) }
      }
    }.recoverTotal{ e => BadRequest(JsError.toFlatJson(e)) }
  }

  def find: EssentialAction = Action { request =>
    val (queryObject, query) = QueryObject.extractQuery(request, defaultDesignDocname, defaultViewName)
    Async {
      res.view(queryObject.docName, queryObject.view).flatMap { view =>
        res.find((view, query))
      }.map( s => Ok(Json.toJson(s.map(_._1))(Writes.seq(res.writer))) )
    }
  }

  def findStream: EssentialAction = Action { request =>
    val (queryObject, query) = QueryObject.extractQuery(request, defaultDesignDocname, defaultViewName)
    Async {
      res.view(queryObject.docName, queryObject.view).map { view =>
        res.findStream((view, query), 0, 0).map(_.map(_._1))
      }.map { s => Ok.stream(
        s.map( it => Json.toJson(it.toSeq)(Writes.seq(res.writer)) ).andThen(Enumerator.eof) )
      }
    }
  }

  def updatePartial(id: String): EssentialAction = Action(parse.json) { request =>
    Json.fromJson[T](request.body)(res.reader).map{ upd =>
      Async{
        res.updatePartial(id, upd).map{ _ => Ok(Json.obj("id" -> id)) }
      }
    }.recoverTotal{ e => BadRequest(JsError.toFlatJson(e)) }
  }

  def batchInsert: EssentialAction = Action(parse.json) { request =>
    Json.fromJson[Seq[T]](request.body)(Reads.seq(res.reader)).map{ elems =>
      Async{
        res.batchInsert(Enumerator(elems:_*)).map{ nb => Ok(Json.obj("nb" -> nb)) }
      }
    }.recoverTotal{ e => BadRequest(JsError.toFlatJson(e)) }
  }

  def batchDelete: EssentialAction = Action { request =>
    val (queryObject, query) = QueryObject.extractQuery(request, defaultDesignDocname, defaultViewName)
    Async {
      res.view(queryObject.docName, queryObject.view).flatMap { view =>
        res.batchDelete((view, query)).map{ _ => Ok("deleted") }
      }
    }
  }

  def batchUpdate: EssentialAction = Action(parse.json) { request =>
    val (queryObject, query) = QueryObject.extractQuery(request, defaultDesignDocname, defaultViewName)
    Json.fromJson[T](request.body)(res.reader).map{ upd =>
      Async{
        res.view(queryObject.docName, queryObject.view).flatMap { view =>
          res.batchUpdate((view, query), upd).map{ _ => Ok("updated") }
        }
      }
    }.recoverTotal{ e => BadRequest(JsError.toFlatJson(e)) }
  }
}




