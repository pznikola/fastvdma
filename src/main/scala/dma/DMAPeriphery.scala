package dma

import chisel3.{fromDoubleToLiteral => _, fromIntToBinaryPoint => _, _}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Config, Field}

import DMAController._
import DMAController.DMAConfig._

/* TestChain Key */
case object DMAChainKey extends Field[Option[DMAChainParams]](None)

// DOC include start: TestChain lazy trait
trait CanHavePeripheryTestChain { this: BaseSubsystem =>
  private val portName = "chain"
  val chain = p(DMAChainKey) match {
    case Some(params) => {
      val chain = LazyModule(new Chain(params.reader, params.writer))
      // Connect Control registers
      pbus.coupleTo(portName) {
        chain.mem.get :=
          AXI4Fragmenter() :=
          AXI4UserYanker() :=
          AXI4Deinterleaver(64) :=
          TLToAXI4() :=
          TLFragmenter(pbus.beatBytes, pbus.blockBytes, holdFirstDeny=true):= _
      }
      // AXI4 read and write nodes
      sbus.coupleFrom("dma_read")  { _ := chain.readNode  }
      sbus.coupleFrom("dma_write") { _ := chain.writeNode }
      // Interrupts
      ibus.fromSync := chain.reader.io_interrupt
      ibus.fromSync := chain.writer.io_interrupt
      // Return
      Some(chain)
    }
    case None => None
  }
}
// DOC include end: TestChain lazy trait

// DOC include start: TestChain imp trait
trait CanHavePeripheryTestChainModuleImp extends LazyRawModuleImp {
  override def provideImplicitClockToLazyChildren = true
  val outer: CanHavePeripheryTestChain
}
// DOC include end: TestChain imp trait

/* Mixin to add TestChain to rocket config */
class WithTestChain(chainParams: DMAChainParams = DMAChainParams(DMAParams.readParams, DMAParams.writeParams))  extends Config((site, here, up) => { case DMAChainKey => Some(chainParams) })