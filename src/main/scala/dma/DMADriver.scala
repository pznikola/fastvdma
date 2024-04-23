package dma

import chisel3.{Bool, IO, Output, Vec}
import chisel3.stage.ChiselStage
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts.{IntSinkParameters, IntSinkPortParameters, IntToBundleBridge}


object DMAChainDriver extends App {

  // Generate verilog
  (new ChiselStage).emitVerilog(
    args = Array(
      "-X", "verilog",
      "-e", "verilog",
      "--target-dir", "verilog/ChainDMA"),
    gen = LazyModule(new AXI4Chain(DMAParams.readParams, DMAParams.writeParams){
      val ioCtrlNode = BundleBridgeSource(() => AXI4Bundle(
        new AXI4BundleParameters(
          addrBits       = DMAParams.readConfig.controlAddrWidth,
          dataBits       = DMAParams.readConfig.controlDataWidth,
          idBits         = 1,
          echoFields     = Nil,
          requestFields  = Nil,
          responseFields = Nil
        ){
          // Bring the globals into scope
          override val lenBits = 0
          override val sizeBits = 0
          override val burstBits = 0
          override val lockBits = 0
          override val cacheBits = 0
          override val protBits = 3
          override val qosBits = 0
          override val respBits = 0
        })
      )
      mem.get := BundleBridgeToAXI4(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "io_control",
          id = IdRange (0,1)
        )))) := ioCtrlNode
      val io_control = InModuleBody { ioCtrlNode.makeIO() }

      reader.io_read match {
        case axi: AXI4MasterNode => {
          val ioInNode = BundleBridgeSink[AXI4Bundle]()
          ioInNode := AXI4ToBundleBridge(AXI4SlavePortParameters(
            Seq(AXI4SlaveParameters(
              address = Seq(DMAParams.w_readAddress),
              regionType = RegionType.UNCACHED,
              executable = true,
              supportsWrite = TransferSizes(1, DMAParams.readConfig.readDataWidth / 8),
              supportsRead = TransferSizes(1, DMAParams.readConfig.readDataWidth / 8)
            )),
            DMAParams.readConfig.readDataWidth / 8)) := axi
          val io_read = InModuleBody {
            ioInNode.makeIO()
          }
        }
      }

      writer.io_write match {
        case axi: AXI4MasterNode => {
          val ioOutNode = BundleBridgeSink[AXI4Bundle]()
          ioOutNode := AXI4ToBundleBridge(AXI4SlavePortParameters(
            Seq(AXI4SlaveParameters(
              address = Seq(DMAParams.w_writeAddress),
              regionType = RegionType.UNCACHED,
              executable = true,
              supportsWrite = TransferSizes(1, DMAParams.writeConfig.writeDataWidth / 8),
              supportsRead = TransferSizes(1, DMAParams.writeConfig.writeDataWidth / 8)
            )),
            DMAParams.writeConfig.writeDataWidth / 8)) := axi
          val io_write = InModuleBody {
            ioOutNode.makeIO()
          }
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