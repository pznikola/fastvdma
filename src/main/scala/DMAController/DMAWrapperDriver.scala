package DMAController

import chisel3._
import DMAController.DMAConfig._
import chisel3.stage.ChiselStage
import freechips.rocketchip.diplomacy.{AddressSet, RegionType, TransferSizes}
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.interrupts._
import org.chipsalliance.diplomacy.bundlebridge.{BundleBridgeSink, BundleBridgeSource}
import org.chipsalliance.diplomacy.lazymodule.{InModuleBody, LazyModule, ModuleValue}

object DMAWrapperDriver extends App {
  // DMA configuration
  val config = new DMAConfig(
    busConfig        = "AXI_AXIL_AXI",
    addrWidth        = 32,
    readDataWidth    = 32,
    writeDataWidth   = 32,
    readMaxBurst     = 16,
    writeMaxBurst    = 16,
    reader4KBarrier  = false,
    writer4KBarrier  = true,
    controlDataWidth = 32,
    controlAddrWidth = 32,
    controlRegCount  = 16,
    fifoDepth        = 512
  )

  // Read and Write address
  private val readAddress  = AddressSet(0x80000000L, 0xFFFF)
  private val writeAddress = AddressSet(0x80010000L, 0xFFFF)
  private val ctrlAddress  = AddressSet(0x80020000L, 0x000F)

  // Generate verilog
  (new ChiselStage).emitVerilog(
    args = Array(
      "-X", "verilog",
      "-e", "verilog",
      "--target-dir", "verilog/LazyDMA"),
    gen = LazyModule(new DMAWrapper(config, ctrlAddress){
      // CSR node IO
      val io_node_csr = BundleBridgeSource(() => AXI4Bundle(AXI4BundleParameters(addrBits = config.controlAddrWidth, dataBits = config.controlDataWidth, idBits = 1)))
      csr_node := BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) := io_node_csr
      val io_csr = InModuleBody { io_node_csr.makeIO() }

      // Reader Node IO
      reader_node match {
        case axis: AXI4StreamSlaveNode => {
          val io_node = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = config.readDataWidth/8)))
          axis := BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = config.readDataWidth / 8)) := io_node
          val io_rd = InModuleBody { io_node.makeIO() }
        }
        case axi: AXI4MasterNode => {
          val io_node = BundleBridgeSink[AXI4Bundle]()
          io_node := AXI4ToBundleBridge(AXI4SlavePortParameters(
            Seq(AXI4SlaveParameters(
              address = Seq(readAddress),
              regionType = RegionType.UNCACHED,
              executable = false,
              supportsWrite = TransferSizes(1, config.readDataWidth / 8),
              supportsRead = TransferSizes(1, config.readDataWidth / 8)
            )), config.readDataWidth / 8)) := axi
          val io_rd = InModuleBody { io_node.makeIO() }
        }
      }

      // Writer Node IO
      writer_node match {
        case axis: AXI4StreamMasterNode => {
          val io_node = BundleBridgeSink[AXI4StreamBundle]()
          io_node := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := axis
          val io_rw = InModuleBody { io_node.makeIO() }
        }
        case axi: AXI4MasterNode => {
          val io_node = BundleBridgeSink[AXI4Bundle]()
          io_node := AXI4ToBundleBridge(AXI4SlavePortParameters(
            Seq(AXI4SlaveParameters(
              address = Seq(writeAddress),
              regionType = RegionType.UNCACHED,
              executable = false,
              supportsWrite = TransferSizes(1, config.writeDataWidth / 8),
              supportsRead  = TransferSizes(1, config.writeDataWidth / 8)
            )), config.writeDataWidth / 8)) := axi
          val io_rw = InModuleBody { io_node.makeIO() }
        }
      }

      // Interrupt Node IO
      val ioIntNode: BundleBridgeSink[Vec[Bool]] = BundleBridgeSink[Vec[Bool]]()
      ioIntNode := IntToBundleBridge(IntSinkPortParameters(Seq(IntSinkParameters(), IntSinkParameters()))) := interrupt_node
      val io_int: ModuleValue[Vec[Bool]] = InModuleBody {
        val io = IO(Output(ioIntNode.bundle.cloneType))
        io.suggestName("int")
        io := ioIntNode.bundle
        io
      }
    }).module
  )
}
