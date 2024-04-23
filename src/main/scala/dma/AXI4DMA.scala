package dma

import DMAController._
import DMAController.DMAConfig.DMAConfig.{AXI, AXIL, AXIS}
import chisel3._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.Parameters

// Chain
class AXI4Chain(
  readerParams: dmaParams,
  writerParams: dmaParams,
) extends LazyModule()(Parameters.empty) {

  require(readerParams.dmaConfig.getBusConfig()._1 == AXI , "Reader side reader must be AXI")
  require(readerParams.dmaConfig.getBusConfig()._2 == AXIL, "Reader control must be AXI Lite")
  require(readerParams.dmaConfig.getBusConfig()._3 == AXIS, "Reader side writer must be AXIS")

  require(writerParams.dmaConfig.getBusConfig()._1 == AXIS, "Writer side reader must be AXIS")
  require(writerParams.dmaConfig.getBusConfig()._2 == AXIL, "Writer control must be AXI Lite")
  require(writerParams.dmaConfig.getBusConfig()._3 == AXI , "Writer side writer must be AXI")

  // Reader DMA
  val reader: DMAWrapper = LazyModule(new DMAWrapper(
    dmaConfig = readerParams.dmaConfig,
    readerParams = readerParams.reader,
    writerParams = readerParams.writer,
    controlParams = readerParams.control
  ))
  // Writer DMA
  val writer: DMAWrapper = LazyModule(new DMAWrapper(
    dmaConfig = writerParams.dmaConfig,
    readerParams = writerParams.reader,
    writerParams = writerParams.writer,
    controlParams = writerParams.control
  ))

  // BUS
  val bus: AXI4Xbar = LazyModule(new AXI4Xbar)
  val mem: Option[AXI4NexusNode] = Some(bus.node)

  (reader.io_write, writer.io_read) match {
    case (axism: AXI4StreamMasterNode, axiss: AXI4StreamSlaveNode) => {
      axiss := axism
    }
  }

  (reader.io_control, writer.io_control) match {
    case (axir: AXI4SlaveNode, axiw: AXI4SlaveNode) => {
      axir := AXI4Buffer() := bus.node
      axiw := AXI4Buffer() := bus.node
    }
  }

  lazy val module = new LazyModuleImp(this) {
    reader.io.sync.readerSync := 0.U
    reader.io.sync.writerSync := 0.U
    writer.io.sync.readerSync := 0.U
    writer.io.sync.writerSync := 0.U
  }
}

