package chipyard.dma


import chisel3.{fromDoubleToLiteral => _, fromIntToBinaryPoint => _, _}
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.tilelink._
import org.chipsalliance.cde.config.{Config, Field}

import DMAController._
import DMAController.DMAConfig._

/* TestChain Params */
case class DMAChainParams (
    reader: dmaParams,
    writer: dmaParams
)

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
      // AXI4 slave node
      chain.reader.io_read match {
        case axis: AXI4SlaveNode => pbus.coupleTo("dma_read") {
          axis :=
            AXI4Fragmenter() :=
            AXI4UserYanker() :=
            AXI4Deinterleaver(4096) :=
            TLToAXI4() := _
        }
      }
      // AXI4 master node
      chain.writer.io_write match {
        case axi: AXI4MasterNode => sbus.coupleFrom("dma_write") {
          _ := AXI4ToTL() := AXI4UserYanker (capMaxFlight = Some(16)) := AXI4Fragmenter() := AXI4IdIndexer (idBits = 4) := axi
        }
      }
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

// DMA configuration
object testDMAParams {
  // DMA configuration
  private val readConfig = new DMAConfig(
    busConfig = "AXI_AXIL_AXIS",
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
  private val r_readAddress = AddressSet(0x60000000, 0x0FFF)
  private val r_writeAddress = AddressSet(0x60001000, 0x0FFF)
  private val r_ctrlAddress = AddressSet(0x60002000, 0x0FFF)

  // Writer addresses
  private val w_readAddress = AddressSet(0x60003000, 0x0FFF)
  private val w_writeAddress = AddressSet(0x60004000, 0x0FFF)
  private val w_ctrlAddress = AddressSet(0x60005000, 0x0FFF)

  // Reader parameters
  val readParams = dmaParams(
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
  val writeParams = dmaParams(
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
}

/* Mixin to add TestChain to rocket config */
class WithTestChain(chainParams: DMAChainParams = DMAChainParams(testDMAParams.readParams, testDMAParams.writeParams))  extends Config((site, here, up) => { case DMAChainKey => Some(chainParams) })