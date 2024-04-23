/*
Copyright (C) 2019-2023 Antmicro

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

SPDX-License-Identifier: Apache-2.0
*/

package DMAController

import chisel3._
import DMAController.DMAConfig._
import chisel3.stage.ChiselStage
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._

object DMAWrapperDriver extends App {
  // DMA configuration
  val config = new DMAConfig(
    busConfig        = "AXI_AXIL_AXI",
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

  // Read and Write address
  private val readAddress  = AddressSet(0x4000, 0x0FFF)
  private val writeAddress = AddressSet(0x0000, 0x0FFF)
  private val ctrlAddress  = AddressSet(0x8000, 0x0FFF)

  // Read node parameters
  private val readParams = readNodeParams(
    AXI4 = Some(Seq(AXI4MasterPortParameters(
      Seq(AXI4MasterParameters(
        name = "io_read",
        id = IdRange(0, 15)))))
    ),
    AXI4Stream = Some(AXI4StreamSlaveParameters())
  )

  // Write node parameters
  private val writeParams = writeNodeParams(
    AXI4 = Some(Seq(AXI4MasterPortParameters(
      Seq(AXI4MasterParameters(
        name = "io_write",
        id   = IdRange(0,15) ))))
    ),
    AXI4Stream = Some(Seq(AXI4StreamMasterPortParameters(
      Seq(AXI4StreamMasterParameters(
        name = "io_write",
        n    = config.writeDataWidth/8 ))))
    )
  )

  // Control node parameters
  private val ctrlParams = controlNodeParams(
    AXI4 = Some(Seq(AXI4SlavePortParameters(
      Seq(AXI4SlaveParameters(
        address       = Seq(ctrlAddress),
        supportsRead  = TransferSizes(1, config.controlDataWidth / 8),
        supportsWrite = TransferSizes(1, config.controlDataWidth / 8),
        interleavedId = Some(0))),
      beatBytes = config.controlDataWidth / 8)))
  )

  // Generate verilog
  (new ChiselStage).emitVerilog(
    args = Array(
      "-X", "verilog",
      "-e", "verilog",
      "--target-dir", "verilog/LazyDMA"),
    gen = LazyModule(new DMAWrapper(config, readParams, writeParams, ctrlParams){
      io_control match {
        case axi: AXI4SlaveNode => {
          val ioInNode = BundleBridgeSource(() => AXI4Bundle(
            AXI4BundleParameters(
              addrBits       = config.controlAddrWidth,
              dataBits       = config.controlDataWidth,
              idBits         = 1,
              echoFields     = Nil,
              requestFields  = Nil,
              responseFields = Nil
            ))
          )
          axi := BundleBridgeToAXI4(AXI4MasterPortParameters(
            Seq(AXI4MasterParameters(
              name = "io_control",
              id = IdRange(0, 0)
            )))) := ioInNode
          val io_control = InModuleBody { ioInNode.makeIO() }
        }
      }
      io_read match {
        case axis: AXI4StreamSlaveNode => {
          val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
          axis := BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = config.readDataWidth/8)) := ioInNode
          val io_read = InModuleBody { ioInNode.makeIO() }
        }
        case axi: AXI4MasterNode => {
          val ioInNode = BundleBridgeSink[AXI4Bundle]()
          ioInNode := AXI4ToBundleBridge(AXI4SlavePortParameters(
            Seq(AXI4SlaveParameters(
              address = Seq(writeAddress),
              regionType = RegionType.UNCACHED,
              executable = false,
              supportsWrite = TransferSizes(1, config.writeDataWidth / 8),
              supportsRead = TransferSizes(1, config.writeDataWidth / 8)
            )),
            config.writeDataWidth / 8)) := axi
          val io_read = InModuleBody {  ioInNode.makeIO() }
        }
      }

      io_write match {
        case axis: AXI4StreamMasterNode => {
          val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()
          ioOutNode := AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) := axis
          val io_write = InModuleBody { ioOutNode.makeIO() }
        }
        case axi: AXI4MasterNode => {
          val ioOutNode = BundleBridgeSink[AXI4Bundle]()
          ioOutNode := AXI4ToBundleBridge(AXI4SlavePortParameters(
            Seq(AXI4SlaveParameters(
              address = Seq(writeAddress),
              regionType = RegionType.UNCACHED,
              executable = false,
              supportsWrite = TransferSizes(1, config.writeDataWidth / 8),
              supportsRead  = TransferSizes(1, config.writeDataWidth / 8)
            )),
            config.writeDataWidth/8)) := axi
          val io_write = InModuleBody { ioOutNode.makeIO() }
        }
      }

      val ioIntNode: BundleBridgeSink[Vec[Bool]] = BundleBridgeSink[Vec[Bool]]()
      ioIntNode := IntToBundleBridge(IntSinkPortParameters(Seq(IntSinkParameters(), IntSinkParameters()))) := io_interrupt
      val io_int: ModuleValue[Vec[Bool]] = InModuleBody {
        val io = IO(Output(ioIntNode.bundle.cloneType))
        io.suggestName("int")
        io := ioIntNode.bundle
        io
      }
    }).module
  )
}
