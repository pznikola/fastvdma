package chipyard.dma

import DMAController._
import DMAController.CSR._
import DMAController.DMAConfig.DMAConfig.{AXI, AXIL, AXIS}
import DMAController.DMAConfig._
import DMAController.Frontend._
import chisel3._
import chisel3.stage.ChiselStage
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import org.chipsalliance.cde.config.Parameters

case class dmaParams (
  reader   : readNodeParams,
  writer   : writeNodeParams,
  control  : controlNodeParams,
  dmaConfig: DMAConfig
)

// Chain
class Chain(
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
    dmaConfig     = readerParams.dmaConfig,
    readerParams  = readerParams.reader,
    writerParams  = readerParams.writer,
    controlParams = readerParams.control
  ))
  // Writer DMA
  val writer: DMAWrapper = LazyModule(new DMAWrapper(
    dmaConfig     = writerParams.dmaConfig,
    readerParams  = writerParams.reader,
    writerParams  = writerParams.writer,
    controlParams = writerParams.control
  ))

  // BUS
  val bus = LazyModule(new AXI4Xbar)
  val mem = Some(bus.node)

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

object ChainDriver extends App {
  // DMA configuration
  private val readConfig = new DMAConfig(
    busConfig        = "AXI_AXIL_AXIS",
    addrWidth        = 32,
    readDataWidth    = 32,
    writeDataWidth   = 32,
    readMaxBurst     = 0,
    writeMaxBurst    = 16,
    reader4KBarrier  = false,
    writer4KBarrier  = true,
    controlDataWidth = 32,
    controlAddrWidth = 32,
    controlRegCount  = 16,
    fifoDepth        = 512
  )
  private val writeConfig = new DMAConfig(
    busConfig = "AXIS_AXIL_AXI",
    addrWidth = 32,
    readDataWidth = 32,
    writeDataWidth = 32,
    readMaxBurst = 0,
    writeMaxBurst = 16,
    reader4KBarrier = false,
    writer4KBarrier = true,
    controlDataWidth = 32,
    controlAddrWidth = 32,
    controlRegCount = 16,
    fifoDepth = 512
  )

  // Reader addresses
  private val r_readAddress  = AddressSet(0x60000000, 0x0FFF)
  private val r_writeAddress = AddressSet(0x60001000, 0x0FFF)
  private val r_ctrlAddress  = AddressSet(0x60002000, 0x0FFF)

  // Writer addresses
  private val w_readAddress  = AddressSet(0x60003000, 0x0FFF)
  private val w_writeAddress = AddressSet(0x60004000, 0x0FFF)
  private val w_ctrlAddress  = AddressSet(0x60005000, 0x0FFF)

  // Reader parameters
  private val readParams = dmaParams(
    reader = readNodeParams(
      AXI4 = Some(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
          address = Seq(r_readAddress),
          supportsRead = TransferSizes(1, readConfig.readDataWidth / 8),
          supportsWrite = TransferSizes(1, readConfig.readDataWidth / 8),
          interleavedId = Some(0))),
        beatBytes = readConfig.readDataWidth / 8))),
      AXI4Stream = Some(AXI4StreamSlaveParameters())
    ),
    writer = writeNodeParams(
      AXI4 = Some(Seq(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "io_write",
          id = IdRange(0, 15)))))
      ),
      AXI4Stream = Some(Seq(AXI4StreamMasterPortParameters(
        Seq(AXI4StreamMasterParameters(
          name = "io_write",
          n = readConfig.writeDataWidth / 8))))
      )
    ),
    control = controlNodeParams(
      AXI4 = Some(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
          address = Seq(r_ctrlAddress),
          supportsRead = TransferSizes(1, readConfig.controlDataWidth / 8),
          supportsWrite = TransferSizes(1, readConfig.controlDataWidth / 8),
          interleavedId = Some(0))),
        beatBytes = readConfig.controlDataWidth / 8)))
    ),
    dmaConfig = readConfig
  )

  // Writer parameters
  private val writeParams = dmaParams(
    reader = readNodeParams(
      AXI4 = Some(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
          address = Seq(w_readAddress),
          supportsRead = TransferSizes(1, writeConfig.readDataWidth / 8),
          supportsWrite = TransferSizes(1, writeConfig.readDataWidth / 8),
          interleavedId = Some(0))),
        beatBytes = writeConfig.readDataWidth / 8))),
      AXI4Stream = Some(AXI4StreamSlaveParameters())
    ),
    writer = writeNodeParams(
      AXI4 = Some(Seq(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "io_write",
          id = IdRange(0, 15)))))
      ),
      AXI4Stream = Some(Seq(AXI4StreamMasterPortParameters(
        Seq(AXI4StreamMasterParameters(
          name = "io_write",
          n = writeConfig.writeDataWidth / 8))))
      )
    ),
    control = controlNodeParams(
      AXI4 = Some(Seq(AXI4SlavePortParameters(
        Seq(AXI4SlaveParameters(
          address = Seq(w_ctrlAddress),
          supportsRead = TransferSizes(1, writeConfig.controlDataWidth / 8),
          supportsWrite = TransferSizes(1, writeConfig.controlDataWidth / 8),
          interleavedId = Some(0))),
        beatBytes = writeConfig.controlDataWidth / 8)))
    ),
    dmaConfig = writeConfig
  )

  // Generate verilog
  (new ChiselStage).emitVerilog(
    args = Array(
      "-X", "verilog",
      "-e", "verilog",
      "--target-dir", "verilog/ChainDMA"),
    gen = LazyModule(new Chain(readParams, writeParams){
      val ioInNode = BundleBridgeSource(() => AXI4Bundle(
        AXI4BundleParameters(
          addrBits       = readConfig.controlAddrWidth,
          dataBits       = readConfig.controlDataWidth,
          idBits         = 1,
          echoFields     = Nil,
          requestFields  = Nil,
          responseFields = Nil
        ))
      )
      mem.get := BundleBridgeToAXI4(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "io_control",
//          id = IdRange(0, 0)
        )))) := ioInNode
      val io_control = InModuleBody { ioInNode.makeIO() }

      reader.io_read match {
        case axi: AXI4SlaveNode => {
          val ioInNode = BundleBridgeSource(() => AXI4Bundle(
            AXI4BundleParameters(
              addrBits = readConfig.addrWidth,
              dataBits = readConfig.readDataWidth,
              idBits   = 4
            ))
          )
          axi := BundleBridgeToAXI4(AXI4MasterPortParameters(
            Seq(AXI4MasterParameters(
              name = "io_read",
              id = IdRange(0,15)
            )))) := ioInNode
          val io_read = InModuleBody { ioInNode.makeIO() }
        }
      }

      writer.io_write match {
        case axi: AXI4MasterNode => {
          val ioOutNode = BundleBridgeSink[AXI4Bundle]()
          ioOutNode := AXI4ToBundleBridge(AXI4SlavePortParameters(
            Seq(AXI4SlaveParameters(
              address = Seq(w_writeAddress),
              regionType = RegionType.UNCACHED,
              executable = false,
              supportsWrite = TransferSizes(1, writeConfig.writeDataWidth / 8),
              supportsRead  = TransferSizes(1, writeConfig.writeDataWidth / 8)
            )),
            writeConfig.writeDataWidth/8)) := axi
          val io_write = InModuleBody { ioOutNode.makeIO() }
        }
      }

      val r_ioIntNode: BundleBridgeSink[Vec[Bool]] = BundleBridgeSink[Vec[Bool]]()
      r_ioIntNode := IntToBundleBridge(IntSinkPortParameters(Seq(IntSinkParameters(), IntSinkParameters()))) := reader.io_interrupt
      val io_r_int: ModuleValue[Vec[Bool]] = InModuleBody {
        val io = IO(Output(r_ioIntNode.bundle.cloneType))
        io.suggestName("int_r")
        io := r_ioIntNode.bundle
        io
      }
      val w_ioIntNode: BundleBridgeSink[Vec[Bool]] = BundleBridgeSink[Vec[Bool]]()
      w_ioIntNode := IntToBundleBridge(IntSinkPortParameters(Seq(IntSinkParameters(), IntSinkParameters()))) := writer.io_interrupt
      val io_w_int: ModuleValue[Vec[Bool]] = InModuleBody {
        val io = IO(Output(w_ioIntNode.bundle.cloneType))
        io.suggestName("int_w")
        io := w_ioIntNode.bundle
        io
      }
    }).module
  )
}
