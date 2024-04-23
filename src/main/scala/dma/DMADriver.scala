package dma

import DMAController.DMAConfig.DMAConfig
import DMAController.{controlNodeParams, readNodeParams, writeNodeParams}
import chisel3.{Bool, IO, Output, Vec}
import chisel3.stage.ChiselStage
import freechips.rocketchip.amba.axi4.{AXI4Bundle, AXI4BundleParameters, AXI4MasterParameters, AXI4MasterPortParameters, AXI4SlaveParameters, AXI4SlavePortParameters, BundleBridgeToAXI4}
import freechips.rocketchip.amba.axi4stream.{AXI4StreamMasterParameters, AXI4StreamMasterPortParameters, AXI4StreamSlaveParameters}
import freechips.rocketchip.diplomacy.{AddressSet, BundleBridgeSink, BundleBridgeSource, IdRange, InModuleBody, LazyModule, ModuleValue, RegionType, TransferSizes}
import freechips.rocketchip.interrupts.{IntSinkParameters, IntSinkPortParameters, IntToBundleBridge}
import freechips.rocketchip.tilelink.{BundleBridgeToTL, TLBundle, TLBundleParameters, TLMasterParameters, TLMasterPortParameters, TLSlaveParameters, TLToBundleBridge}

object DMAChainDriver extends App {

  // Generate verilog
  (new ChiselStage).emitVerilog(
    args = Array(
      "-X", "verilog",
      "-e", "verilog",
      "--target-dir", "verilog/ChainDMA"),
    gen = LazyModule(new Chain(DMAParams.readParams, DMAParams.writeParams){
      val ioInNode = BundleBridgeSource(() => AXI4Bundle(
        AXI4BundleParameters(
          addrBits       = DMAParams.readConfig.controlAddrWidth,
          dataBits       = DMAParams.readConfig.controlDataWidth,
          idBits         = 1,
          echoFields     = Nil,
          requestFields  = Nil,
          responseFields = Nil
        ))
      )
      mem.get := BundleBridgeToAXI4(AXI4MasterPortParameters(
        Seq(AXI4MasterParameters(
          name = "io_control",
        )))) := ioInNode
      val io_control = InModuleBody { ioInNode.makeIO() }

      val ioReadNode = BundleBridgeSink[TLBundle]()
      ioReadNode := TLToBundleBridge(
        TLSlaveParameters.v1(
          address = Seq(DMAParams.r_readAddress),
          resources = writer.dtsdevice.reg,
          regionType = RegionType.UNCACHED,
          executable = false,
          supportsArithmetic = TransferSizes(1, 4),
          supportsLogical = TransferSizes(1, 4),
          supportsGet = TransferSizes(1, 4),
          supportsPutFull = TransferSizes(1, 4),
          supportsPutPartial = TransferSizes(1, 4),
          supportsHint = TransferSizes(1, 4),
          fifoId = Some(0)
        ),
        4
      ) := readNode
      val io_read = InModuleBody {
        ioReadNode.makeIO()
      }

      val ioWriteNode = BundleBridgeSink[TLBundle]()
      ioWriteNode := TLToBundleBridge(
        TLSlaveParameters.v1(
          address = Seq(DMAParams.w_writeAddress),
          resources = writer.dtsdevice.reg,
          regionType = RegionType.UNCACHED,
          executable = false,
          supportsArithmetic = TransferSizes(1, 4),
          supportsLogical = TransferSizes(1, 4),
          supportsGet = TransferSizes(1, 4),
          supportsPutFull = TransferSizes(1, 4),
          supportsPutPartial = TransferSizes(1, 4),
          supportsHint = TransferSizes(1, 4),
          fifoId = Some(0)
        ),
        4
      ) := writeNode
      val io_write = InModuleBody { ioWriteNode.makeIO() }

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