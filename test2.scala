package tensor

// This file: Halide in Scala

object Test2 {
    import scala.reflect.ClassTag

    type UByte = Int

    abstract class Type[T] {
      def classTag: ClassTag[T]
      def fromByte(d: Byte): T
      def fromInt(d: Int): T
      def fromDouble(d: Double): T
      def plus(a: T, b: T): T
      def times(a: T, b: T): T
      def min(a: T, b: T): T
    }

    implicit object IntTyp extends Type[Int] {
      def classTag = ClassTag.Int
      def fromByte(d: Byte) = d
      def fromInt(d: Int) = d
      def fromDouble(d: Double) = d.toInt
      def plus(a: Int, b: Int) = a + b
      def times(a: Int, b: Int) = a * b
      def min(a: Int, b: Int) = a min b
    }

    implicit object DoubleTyp extends Type[Double] {
      def classTag = ClassTag.Double
      def fromByte(d: Byte) = d
      def fromInt(d: Int) = d
      def fromDouble(d: Double) = d
      def plus(a: Double, b: Double) = a + b
      def times(a: Double, b: Double) = a * b
      def min(a: Double, b: Double) = a min b
    }

    implicit object ByteTyp extends Type[Byte] {
      def classTag = ClassTag.Byte
      def fromByte(d: Byte) = d
      def fromInt(d: Int) = d.toByte
      def fromDouble(d: Double) = d.toByte
      def plus(a: Byte, b: Byte) = (a + b).toByte
      def times(a: Byte, b: Byte) = (a * b).toByte
      def min(a: Byte, b: Byte) = (a min b).toByte
    }

    implicit def extractClassTag[T](implicit tpe: Type[T]): ClassTag[T] = tpe.classTag

    implicit def doubleExpr(d: Double): Expr = Const(d)

    abstract class Expr {
      def +(that: Expr): Expr = Plus(this,that)
      def *(that: Expr): Expr = Times(this,that)
      def toDouble: Expr = ToDouble(this)
      def toInt: Expr = ToInt(this)
      def toByte: Expr = ToByte(this)
    }

    def min(a: Expr, b: Expr): Expr = Min(a,b)

    case class Var(s: String) extends Expr { override def toString = s }
    case class Apply(x: Func, y: List[Expr]) extends Expr
    case class Apply3(f: Buffer3[_], x: Expr, y: Expr, c: Expr) extends Expr

    case class Const(x: Double) extends Expr
    case class Plus(x: Expr, y: Expr) extends Expr
    case class Times(x: Expr, y: Expr) extends Expr
    case class Min(x: Expr, y: Expr) extends Expr
    case class ToDouble(x: Expr) extends Expr
    case class ToInt(x: Expr) extends Expr
    case class ToByte(x: Expr) extends Expr

    def eval[T](g: Expr)(implicit tpe: Type[T], env: Map[String,Int]): T = g match {
      case Const(c)   => tpe.fromDouble(c)
      case Var(s)     => tpe.fromInt(env(s))
      case Plus(a,b)  => tpe.plus(eval[T](a), eval[T](b))
      case Times(a,b) => tpe.times(eval[T](a), eval[T](b))
      case Min(a,b)   => tpe.min(eval[T](a), eval[T](b))
      case ToDouble(a)=> tpe.fromDouble(eval[Double](a))
      case ToInt(a)   => tpe.fromInt(eval[Int](a))
      case ToByte(a)  => tpe.fromByte(eval[Byte](a))
      case Apply3(f,a,b,c) => tpe.fromInt(f(eval[Int](a), eval[Int](b), eval[Int](c)).asInstanceOf[UByte]) // assume byte
    }

    case class FuncInternal(x: Var, y: Var, body: Expr)

    case class Func(s: String) {
      var impl: FuncInternal = _

      def update(x: Var, y: Var, body: Expr): Unit = {
        impl = FuncInternal(x,y,body)
      }

      def realize[T:Type](w: Int, h: Int): Buffer[T] = {
        val buf = new Buffer[T](w, h)
        var bounds = Map(impl.x.s -> w, impl.y.s -> h)

        def loop(vs: List[String], env: Map[String,Int])(f: Map[String,Int] => Unit): Unit = vs match {
          case Nil => f(env)
          case x::vs => for (i <- 0 until bounds(x)) loop(vs, env + (x -> i))(f)
        }

        loop(order, Map()) { e0 =>
          implicit var env = e0
          val i = env(impl.x.s)
          val j = env(impl.y.s)
          if (trace) println(s"$i,$j")
          buf(i,j) = eval[T](impl.body)
        }
        buf
      }

      def print_loop_nest(): Unit = {
        for (x <- order)
          println(s"for ${x}:")
        println(s"$s(...) = ...")
      }

    }

    case class Func3Internal(x: Var, y: Var, c: Var, body: Expr)

    case class Func3() {
      var impl: Func3Internal = _

      def update(x: Var, y: Var, c: Var, body: Expr): Unit = {
        this.impl = Func3Internal(x,y,c,body)
      }

      def realize[T:Type](w: Int, h: Int, d: Int): Buffer3[T] = {
        val buf = new Buffer3[T](w, h, d)
        for (j <- 0 until h; i <- 0 until w; c <- 0 until d) {
          implicit val env = Map(impl.x.s -> i, impl.y.s -> j, impl.c.s -> c)
          buf(i,j,c) = eval[T](impl.body)
        }
        buf
      }
    }

    class Buffer[T:Type](val width: Int, val height: Int) {
      val data: Array[Array[T]] = Array.ofDim[T](height, width)
      def apply(x: Int, y: Int) = data(y)(x)
      def update(x: Int, y: Int, v: T) = data(y)(x) = v
    }

    class Buffer3[T:Type](val width: Int, val height: Int, val channels: Int) {
      val data: Array[Array[Array[T]]] = Array.ofDim[T](height, width, channels)
      def apply(x: Int, y: Int, c: Int) = data(y)(x)(c)
      def update(x: Int, y: Int, c: Int, v: T) = data(y)(x)(c) = v

      def apply(x: Var, y: Var, c: Var) = Apply3(this,x,y,c)
    }

    def load_image(file: String): Buffer3[UByte] = {
      import java.io.File
      import java.awt.image._
      import javax.imageio._
      val imageFile = new File(file)
      val image: BufferedImage = ImageIO.read(imageFile)

      val buf = new Buffer3[UByte](image.getWidth, image.getHeight, 3) // todo: grayscale?? alpha?
      for (j <- 0 until image.getHeight; i <- 0 until image.getWidth) {
        val rgb = image.getRGB(i,j)
        buf(i,j,0) = ((rgb >>> 16) & 0xFF)//.toByte
        buf(i,j,1) = ((rgb >>> 8) & 0xFF)//.toByte
        buf(i,j,2) = ((rgb >>> 0) & 0xFF)//.toByte
      }

      buf
    }

    def save_image(buf: Buffer3[UByte], file: String): Unit = {
      assert(buf.channels == 3) // greyscale? alpha?

      import java.io.File
      import java.awt.image._
      import javax.imageio._
      val imageFile = new File(file)
      val image: BufferedImage = new BufferedImage(buf.width, buf.height, BufferedImage.TYPE_INT_RGB)

      for (j <- 0 until image.getHeight; i <- 0 until image.getWidth) {
        val rgb = ((buf(i,j,0) & 0xFF) << 16) | ((buf(i,j,1) & 0xFF) << 8) | ((buf(i,j,2) & 0xFF) << 0)
        image.setRGB(i,j,rgb)
      }

      ImageIO.write(image, "png", imageFile)
    }


    def test1(): Unit = {
      val x = Var("x")
      val y = Var("y")

      val gradient = Func("gradient")

      val e = x + y

      gradient(x, y) = e

      val output = gradient.realize[Int](800, 600)

      // test
      for (j <- 0 until output.height) {
          for (i <- 0 until output.width) {
              if (output(i, j) != i + j) {
                  println("Something went wrong!\n"+
                         s"Pixel $i, $j was supposed to be ${i+j}, but instead it's ${output(i, j)}")
                  return
              }
          }
      }
      println("Success!")
    }

    def test2(): Unit = {
      val x = Var("x")
      val y = Var("y")
      val c = Var("c")

      val img = load_image("/Users/me/Desktop/tryout/halide/tutorial/images/rgb.png")

      val brighter = Func3()

      // Note: we don't have a proper UByte type in Scala, hence map it to Int
      // Note: .toDouble introduces an expected type: all subexprs evaluated as double

      brighter(x,y,c) = min((img(x,y,c) * 1.5), 255.0).toDouble

      val output = brighter.realize[UByte](img.width, img.height, img.channels)

      save_image(output,"brighter.png")

      println("Success!")
    }

  def main(args: Array[String]): Unit = {
    test1()
    test2()

    println("done")
  }
}