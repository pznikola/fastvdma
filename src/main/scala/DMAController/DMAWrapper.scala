package DMAController

import DMAController.CSR._
import DMAController.DMAConfig.DMAConfig.{AXI, AXIL, AXIS}
import DMAController.DMAConfig._
import DMAController.Frontend._
import DMAController.Worker.{SyncBundle, WorkerCSRWrapper}
import DMAUtils._
import chisel3._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import org.chipsalliance.cde.config.Parameters

// Read Node Parameters
case class readNodeParams (
  AXI4:       Option[Seq[AXI4SlavePortParameters]],
  AXI4Stream: Option[AXI4StreamSlaveParameters]
)

// Write Node Parameters
case class writeNodeParams (
  AXI4:       Option[Seq[AXI4MasterPortParameters]],
  AXI4Stream: Option[Seq[AXI4StreamMasterPortParameters]]
)

// Control Node Parameters
case class controlNodeParams (
  AXI4: Option[Seq[AXI4SlavePortParameters]]
)

// DMA Wrapper
class DMAWrapper(
  dmaConfig:     DMAConfig,
  readerParams:  readNodeParams,
  writerParams:  writeNodeParams,
  controlParams: controlNodeParams
) extends LazyModule()(Parameters.empty) {

  val (reader, ccsr, writer) = dmaConfig.getBusConfig()

  require(reader == AXI || reader == AXIS , "Reader must be either AXI or AXIS")
  require(ccsr == AXIL, "Control must be AXI Lite")
  require(writer == AXI || writer == AXIS , "Writer must be either AXI or AXIS")

  // DTS
  val dtsdevice = new SimpleDevice("fastdma", Seq("antmicro, fastdma_" + reader + ccsr + writer))
  // Nodes
  val io_read      = if (reader == AXI) AXI4SlaveNode(readerParams.AXI4.get) else AXI4StreamSlaveNode(readerParams.AXI4Stream.get)
  val io_write     = if (writer == AXI) AXI4MasterNode(writerParams.AXI4.get) else AXI4StreamMasterNode(writerParams.AXI4Stream.get)
  val io_control   = AXI4SlaveNode(controlParams.AXI4.get)
  val io_interrupt = IntSourceNode(IntSourcePortSimple(num = 2, resources = dtsdevice.int))

  lazy val io = IO(new Bundle {
    val sync = new SyncBundle
  })

  lazy val module = new LazyModuleImp(this) {

    val (readNode , _) = io_read.in.head
    val (writeNode, _) = io_write.out.head
    val (ctrlNode , _) = io_control.in.head
    val (intNode  , _) = io_interrupt.out.head

    val Bus = new Bus(dmaConfig)

    val csrFrontend    = Module(Bus.getCSR(ccsr))
    val readerFrontend = Module(Bus.getReader(reader))
    val writerFrontend = Module(Bus.getWriter(writer))

    val csr = Module(new CSR(dmaConfig))
    val ctl = Module(new WorkerCSRWrapper(dmaConfig))

    val queue = DMAQueue(readerFrontend.io.dataIO, dmaConfig)
    queue <> writerFrontend.io.dataIO

    csr.io.bus <> csrFrontend.io.bus
    ctl.io.csr <> csr.io.csr
    readerFrontend.io.xfer <> ctl.io.xferRead
    writerFrontend.io.xfer <> ctl.io.xferWrite

    (readerFrontend.io.bus, readNode) match {
      case (axis: DMAController.Bus.AXIStream, node_axis: AXI4StreamBundle) => {
        axis.tdata            := node_axis.bits.data
        axis.tvalid           := node_axis.valid
        axis.tuser            := node_axis.bits.user
        axis.tlast            := node_axis.bits.last
        node_axis.ready       := axis.tready
      }
      case (axi: DMAController.Bus.AXI4, node_axi: AXI4Bundle) => {
        // AW channel
        node_axi.aw.bits.id    := axi.aw.awid
        node_axi.aw.bits.addr  := axi.aw.awaddr
        node_axi.aw.bits.len   := axi.aw.awlen
        node_axi.aw.bits.size  := axi.aw.awsize
        node_axi.aw.bits.burst := axi.aw.awburst
        node_axi.aw.bits.lock  := axi.aw.awlock
        node_axi.aw.bits.cache := axi.aw.awcache
        node_axi.aw.bits.prot  := axi.aw.awprot
        node_axi.aw.bits.qos   := axi.aw.awqos
        node_axi.aw.valid      := axi.aw.awvalid
        axi.aw.awready         := node_axi.aw.ready
        // W channel
        node_axi.w.bits.data   := axi.w.wdata
        node_axi.w.bits.strb   := axi.w.wstrb
        node_axi.w.bits.last   := axi.w.wlast
        node_axi.w.valid       := axi.w.wvalid
        axi.w.wready           := node_axi.w.ready
        // B channel
        axi.b.bid              := node_axi.b.bits.id
        axi.b.bresp            := node_axi.b.bits.resp
        axi.b.bvalid           := node_axi.b.valid
        node_axi.b.ready       := axi.b.bready
        // AR channel
        node_axi.ar.bits.id    := axi.ar.arid
        node_axi.ar.bits.addr  := axi.ar.araddr
        node_axi.ar.bits.len   := axi.ar.arlen
        node_axi.ar.bits.size  := axi.ar.arsize
        node_axi.ar.bits.burst := axi.ar.arburst
        node_axi.ar.bits.lock  := axi.ar.arlock
        node_axi.ar.bits.cache := axi.ar.arcache
        node_axi.ar.bits.prot  := axi.ar.arprot
        node_axi.ar.bits.qos   := axi.ar.arqos
        node_axi.ar.valid      := axi.ar.arvalid
        axi.ar.arready         := node_axi.ar.ready
        // R channel
        axi.r.rid              := node_axi.r.bits.id
        axi.r.rdata            := node_axi.r.bits.data
        axi.r.rresp            := node_axi.r.bits.resp
        axi.r.rlast            := node_axi.r.bits.last
        axi.r.rvalid           := node_axi.r.valid
        node_axi.r.ready       := axi.r.rready
      }
    }

    (writerFrontend.io.bus, writeNode) match {
      case (axis: DMAController.Bus.AXIStream, node_axis: AXI4StreamBundle) => {
        node_axis.bits.data    := axis.tdata
        node_axis.valid        := axis.tvalid
        node_axis.bits.user    := axis.tuser
        node_axis.bits.last    := axis.tlast
        axis.tready            := node_axis.ready
      }
      case (axi: DMAController.Bus.AXI4, node_axi: AXI4Bundle) => {
        // AW channel
        node_axi.aw.bits.id    := axi.aw.awid
        node_axi.aw.bits.addr  := axi.aw.awaddr
        node_axi.aw.bits.len   := axi.aw.awlen
        node_axi.aw.bits.size  := axi.aw.awsize
        node_axi.aw.bits.burst := axi.aw.awburst
        node_axi.aw.bits.lock  := axi.aw.awlock
        node_axi.aw.bits.cache := axi.aw.awcache
        node_axi.aw.bits.prot  := axi.aw.awprot
        node_axi.aw.bits.qos   := axi.aw.awqos
        node_axi.aw.valid      := axi.aw.awvalid
        axi.aw.awready         := node_axi.aw.ready
        // W channel
        node_axi.w.bits.data   := axi.w.wdata
        node_axi.w.bits.strb   := axi.w.wstrb
        node_axi.w.bits.last   := axi.w.wlast
        node_axi.w.valid       := axi.w.wvalid
        axi.w.wready           := node_axi.w.ready
        // B channel
        axi.b.bid              := node_axi.b.bits.id
        axi.b.bresp            := node_axi.b.bits.resp
        axi.b.bvalid           := node_axi.b.valid
        node_axi.b.ready       := axi.b.bready
        // AR channel
        node_axi.ar.bits.id    := axi.ar.arid
        node_axi.ar.bits.addr  := axi.ar.araddr
        node_axi.ar.bits.len   := axi.ar.arlen
        node_axi.ar.bits.size  := axi.ar.arsize
        node_axi.ar.bits.burst := axi.ar.arburst
        node_axi.ar.bits.lock  := axi.ar.arlock
        node_axi.ar.bits.cache := axi.ar.arcache
        node_axi.ar.bits.prot  := axi.ar.arprot
        node_axi.ar.bits.qos   := axi.ar.arqos
        node_axi.ar.valid      := axi.ar.arvalid
        axi.ar.arready         := node_axi.ar.ready
        // R channel
        axi.r.rid              := node_axi.r.bits.id
        axi.r.rdata            := node_axi.r.bits.data
        axi.r.rresp            := node_axi.r.bits.resp
        axi.r.rlast            := node_axi.r.bits.last
        axi.r.rvalid           := node_axi.r.valid
        node_axi.r.ready       := axi.r.rready
      }
    }

    (csrFrontend.io.ctl, ctrlNode) match {
      case (axi: DMAController.Bus.AXI4Lite, node_axi: AXI4Bundle) => {
        // AW channel
        axi.aw.awaddr          := node_axi.aw.bits.addr
        axi.aw.awprot          := node_axi.aw.bits.prot
        axi.aw.awvalid         := node_axi.aw.valid
        node_axi.aw.ready      := axi.aw.awready
        // W channel
        axi.w.wdata            := node_axi.w.bits.data
        axi.w.wstrb            := node_axi.w.bits.strb
        axi.w.wvalid           := node_axi.w.valid
        node_axi.w.ready       := axi.w.wready
        // B channel
        node_axi.b.bits.resp   := axi.b.bresp
        node_axi.b.valid       := axi.b.bvalid
        axi.b.bready           := node_axi.b.ready
        // AR channel
        axi.ar.araddr          := node_axi.ar.bits.addr
        axi.ar.arprot          := node_axi.ar.bits.prot
        axi.ar.arvalid         := node_axi.ar.valid
        node_axi.ar.ready      := axi.ar.arready
        // R channel
        node_axi.r.bits.data   := axi.r.rdata
        node_axi.r.bits.resp   := axi.r.rresp
        node_axi.r.valid       := axi.r.rvalid
        axi.r.rready           := node_axi.r.ready
      }
    }

    intNode(0) := ctl.io.irq.readerDone
    intNode(1) := ctl.io.irq.writerDone

    io.sync <> ctl.io.sync
  }
}